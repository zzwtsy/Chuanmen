//! 单活动会话；后台命令和保护回调分别排空，停止未确认时不释放会话资源。
use easytier::{
    android_tun::{TunAttachment, TunIdentity, TunPhase, TunRequest},
    common::config::TomlConfigLoader,
    instance::factory::{NativeCoreInstance, enqueue_android_tun},
};
use easytier_core::instance::CoreInstanceState;
use easytier_ffi::android_host as host;
use jni::{JNIEnv, objects::JObject};
use serde_json::{Value, json};
use std::sync::{Arc, Condvar, LazyLock, Mutex};

use crate::{
    protector::Protector,
    protocol::{Request, failure, reply},
};

struct State {
    instance: Option<Arc<NativeCoreInstance>>,
    attachment: Option<Arc<TunAttachment>>,
    phase: &'static str,
    closing: bool,
    commands: usize,
    last_operation: u64,
    last_attachment: u64,
}

struct Session {
    id: String,
    generation: u64,
    revision: u64,
    state: Mutex<State>,
    idle: Condvar,
    stop_lock: Mutex<()>,
    tun_lock: Mutex<()>,
    protector: Mutex<Option<Arc<Protector>>>,
}

static CURRENT: LazyLock<Mutex<Option<Arc<Session>>>> = LazyLock::new(|| Mutex::new(None));

#[cfg(test)]
static CREATIONS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

#[cfg(test)]
pub(crate) fn creation_count() -> u64 {
    CREATIONS.load(std::sync::atomic::Ordering::SeqCst)
}

struct CommandGuard(Arc<Session>);
impl Drop for CommandGuard {
    fn drop(&mut self) {
        let mut state = self
            .0
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state.commands -= 1;
        self.0.idle.notify_all();
    }
}

fn current(request: &Request) -> Result<Arc<Session>, &'static str> {
    let (id, generation, _) = request.identity()?;
    let session = CURRENT
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .clone()
        .ok_or("not_running")?;
    if session.id != id || session.generation != generation {
        return Err("stale_session");
    }
    Ok(session)
}

fn enter(session: &Arc<Session>) -> Result<CommandGuard, &'static str> {
    let mut state = session
        .state
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if state.closing {
        return Err("session_closing");
    }
    state.commands += 1;
    Ok(CommandGuard(session.clone()))
}

pub fn start(env: &mut JNIEnv<'_>, callback: &JObject<'_>, request: &Request) -> Value {
    let (id, generation, operation) = match request.identity() {
        Ok(value) => value,
        Err(code) => return failure(request, "failed", code),
    };
    let revision = match request
        .saved_revision
        .filter(|value| *value > 0 && *value <= i64::MAX as u64)
    {
        Some(revision) => revision,
        None => return failure(request, "failed", "invalid_revision"),
    };
    let config = match request
        .config
        .as_deref()
        .and_then(|config| TomlConfigLoader::new_from_str(config).ok())
    {
        Some(config) => config,
        None => return failure(request, "failed", "invalid_config"),
    };
    if callback.is_null() {
        return failure(request, "failed", "protector_unavailable");
    }
    let protector = match env.get_java_vm().and_then(|vm| {
        env.new_global_ref(callback).map(|callback| Protector {
            gate: Default::default(),
            vm,
            callback,
            session_id: id.clone(),
            generation: generation as i64,
            calls: Default::default(),
            failures: Default::default(),
        })
    }) {
        Ok(protector) => Arc::new(protector),
        Err(_) => return failure(request, "failed", "jni_error"),
    };
    #[cfg(test)]
    CREATIONS.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    let session = Arc::new(Session {
        id,
        generation,
        revision,
        state: Mutex::new(State {
            instance: None,
            attachment: None,
            phase: "starting",
            closing: false,
            commands: 1,
            last_operation: operation,
            last_attachment: 0,
        }),
        idle: Condvar::new(),
        stop_lock: Mutex::new(()),
        tun_lock: Mutex::new(()),
        protector: Mutex::new(Some(protector.clone())),
    });
    {
        let mut current = CURRENT
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if current.as_ref().is_some_and(|session| {
            session
                .state
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .phase
                != "released"
        }) {
            return failure(request, "rejected", "busy");
        }
        easytier::socket_protector::set_native_socket_protector(Some(protector));
        *current = Some(session.clone());
    }
    let _command = CommandGuard(session.clone());
    let instance = {
        let mut state = session
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.closing {
            return failure(request, "failed", "session_closing");
        }
        match host::block_on(async { host::manager().create(config, ()) }) {
            Ok(instance) => {
                state.instance = Some(instance.clone());
                instance
            }
            Err(_) => {
                state.phase = "failed";
                return failure(request, "failed", "partial_initialization");
            }
        }
    };
    let result = host::block_on(instance.start());
    let mut state = session
        .state
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if state.closing {
        return failure(request, "failed", "session_closing");
    }
    if result.is_err() {
        state.phase = "failed";
        return failure(request, "failed", "native_failure");
    }
    state.phase = "core_running";
    reply(
        request,
        "confirmed",
        json!({"core_started": true, "saved_revision": revision}),
    )
}

pub fn attach(request: &Request, detach: bool) -> Value {
    let session = match current(request) {
        Ok(session) => session,
        Err(code) => return failure(request, "failed", code),
    };
    let _command = match enter(&session) {
        Ok(guard) => guard,
        Err(code) => return failure(request, "failed", code),
    };
    let _serial = session
        .tun_lock
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let (_, _, operation) = match request.identity() {
        Ok(value) => value,
        Err(code) => return failure(request, "failed", code),
    };
    let attachment_sequence = match request
        .attachment
        .filter(|value| *value > 0 && *value <= i64::MAX as u64)
    {
        Some(value) => value,
        None => return failure(request, "failed", "invalid_attachment"),
    };
    let (instance, attachment) = {
        let mut state = session
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if state.closing {
            return failure(request, "failed", "session_closing");
        }
        let Some(instance) = state.instance.clone() else {
            return failure(request, "failed", "not_running");
        };
        if instance.state() != CoreInstanceState::Running {
            return failure(request, "failed", "not_running");
        }
        if detach {
            let Some(attachment) = state.attachment.clone() else {
                return failure(request, "failed", "invalid_attachment");
            };
            if attachment.identity.attachment != attachment_sequence {
                return failure(request, "failed", "stale_attachment");
            }
            (instance, attachment)
        } else {
            if attachment_sequence <= state.last_attachment
                || state.attachment.as_ref().is_some_and(|value| {
                    matches!(value.phase(), TunPhase::Pending | TunPhase::Installed)
                })
            {
                return failure(request, "failed", "stale_attachment");
            }
            if request.fd.is_none_or(|fd| fd < 0) {
                return failure(request, "failed", "invalid_fd");
            }
            let attachment = TunAttachment::new(TunIdentity {
                session_id: session.id.clone(),
                generation: session.generation,
                operation,
                attachment: attachment_sequence,
            });
            state.last_attachment = attachment_sequence;
            state.attachment = Some(attachment.clone());
            (instance, attachment)
        }
    };
    let (pending, receipt) = TunRequest::new(
        if detach { None } else { request.fd },
        if detach {
            None
        } else {
            Some(attachment.clone())
        },
    );
    if enqueue_android_tun(&instance, pending).is_err() {
        if !detach {
            attachment.released();
        }
        return failure(request, "failed", "queue_closed");
    }
    let result = host::block_on(receipt);
    match result {
        Ok(Ok(())) => {
            let mut state = session
                .state
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            if detach {
                if attachment.phase() != TunPhase::Released {
                    return failure(request, "unknown", "borrow_unconfirmed");
                }
                state.attachment = None;
                if !state.closing {
                    state.phase = "core_running";
                }
            } else {
                if attachment.phase() != TunPhase::Installed {
                    return failure(request, "unknown", "install_unconfirmed");
                }
                if !state.closing {
                    state.phase = "running";
                    state.last_operation = operation;
                }
            }
            reply(
                request,
                "confirmed",
                json!({"borrow_ended": detach, "installed": !detach, "attachment": attachment_sequence}),
            )
        }
        Ok(Err(error)) => failure(
            request,
            "failed",
            match error {
                easytier::android_tun::TunError::Cancelled => "cancelled",
                easytier::android_tun::TunError::QueueClosed => "queue_closed",
                easytier::android_tun::TunError::InstallFailed => "tun_install_failed",
            },
        ),
        Err(_) => failure(request, "unknown", "receipt_lost"),
    }
}

pub fn stop(request: &Request) -> Value {
    let session = match current(request) {
        Ok(session) => session,
        Err(code) => return failure(request, "failed", code),
    };
    let _stop = session
        .stop_lock
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let instance = {
        let mut state = session
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if matches!(state.phase, "stopped" | "released") {
            return reply(request, "confirmed", json!({"drained": true}));
        }
        state.closing = true;
        state.phase = "stopping";
        state.instance.clone()
    };
    if let Some(protector) = session
        .protector
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .as_ref()
    {
        protector.gate.close_and_wait();
    }
    if let Some(instance) = instance.as_ref() {
        let result =
            host::block_on(host::manager().delete_network_instances([instance.instance_id()]));
        if result.is_err() {
            return failure(request, "unknown", "stop_unconfirmed");
        }
    }
    let mut state = session
        .state
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    while state.commands > 0 {
        state = session
            .idle
            .wait(state)
            .unwrap_or_else(std::sync::PoisonError::into_inner);
    }
    if state.attachment.as_ref().is_some_and(|attachment| {
        matches!(attachment.phase(), TunPhase::Pending | TunPhase::Installed)
    }) {
        return failure(request, "unknown", "borrow_unconfirmed");
    }
    state.attachment = None;
    state.instance = None;
    drop(instance);
    crate::protector::deny_new_sockets();
    state.phase = "stopped";
    reply(
        request,
        "confirmed",
        json!({"drained": true, "active_instances": host::manager().instance_ids().len(),
        "tun_borrows": 0, "callbacks_in_flight": 0, "jni_global_refs": 1}),
    )
}

/// Kotlin 关闭 PFD 后调用；单独确认引用释放，避免跨语言资源关闭顺序倒置。
pub fn release(request: &Request) -> Value {
    let session = match current(request) {
        Ok(session) => session,
        Err(code) => return failure(request, "failed", code),
    };
    let _stop = session
        .stop_lock
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut state = session
        .state
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if !matches!(state.phase, "stopped" | "released")
        || state.commands != 0
        || state.attachment.is_some()
    {
        return failure(request, "unknown", "borrow_unconfirmed");
    }
    let mut protector = session
        .protector
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if protector
        .as_ref()
        .is_some_and(|protector| Arc::strong_count(protector) != 1)
    {
        return failure(request, "unknown", "jni_refs_in_flight");
    }
    protector.take();
    state.phase = "released";
    reply(
        request,
        "confirmed",
        json!({"released": true, "jni_global_refs": 0}),
    )
}

pub fn query(request: &Request) -> Value {
    let session = match current(request) {
        Ok(session) => session,
        Err(code) => return failure(request, "failed", code),
    };
    let stopped = matches!(
        session
            .state
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .phase,
        "stopped" | "released"
    );
    let _command = if stopped {
        None
    } else {
        match enter(&session) {
            Ok(guard) => Some(guard),
            Err(code) => return failure(request, "unknown", code),
        }
    };
    let state = session
        .state
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let instance = state.instance.clone();
    let phase = state.phase;
    let attachment = state.attachment.clone();
    let last_operation = state.last_operation;
    drop(state);
    let info = instance
        .as_ref()
        .and_then(|instance| host::block_on(host::manager().network_info(instance.instance_id())));
    let node = info.as_ref().and_then(|info| info.my_node_info.as_ref());
    let ipv4 = node
        .and_then(|node| node.virtual_ipv4.as_ref())
        .map(ToString::to_string);
    let routes: Vec<Value> = info
        .as_ref()
        .map(|info| {
            info.routes.iter().map(|route| json!({
        "peer_id": route.peer_id, "ipv4": route.ipv4_addr.as_ref().map(ToString::to_string),
        "proxy_cidrs": route.proxy_cidrs, "cost": route.cost, "version": route.version,
    })).collect()
        })
        .unwrap_or_default();
    let peers: Vec<Value> = info
        .as_ref()
        .map(|info| {
            info.peers
                .iter()
                .map(|peer| {
                    json!({
                        "peer_id": peer.peer_id, "connections": peer.conns.len(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    let protector = session
        .protector
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    reply(
        request,
        "confirmed",
        json!({
            "phase": phase, "core_state": instance.as_ref().map(|instance| format!("{:?}", instance.state()).to_lowercase()),
            "saved_revision": session.revision, "last_operation": last_operation,
            "ipv4": ipv4, "routes": routes, "peers": peers,
            "udp_nat_type": node.and_then(|node| node.stun_info.as_ref()).map(|stun| stun.udp_nat_type),
            "tcp_nat_type": node.and_then(|node| node.stun_info.as_ref()).map(|stun| stun.tcp_nat_type),
            "attachment": attachment.as_ref().map(|attachment| &attachment.identity),
            "attachment_phase": attachment.as_ref().map(|attachment| attachment.phase()),
            "tun_counters": attachment.as_ref().map(|attachment| attachment.counters()),
            "active_instances": host::manager().instance_ids().len(),
            "callbacks_in_flight": protector.as_ref().map(|protector| protector.gate.count()).unwrap_or(0),
            "jni_global_refs": usize::from(protector.is_some()),
            "runtime_threads": host::runtime_threads(),
            "protection_calls": protector.as_ref().map(|protector| protector.calls.load(std::sync::atomic::Ordering::Relaxed)).unwrap_or(0),
            "protection_failures": protector.as_ref().map(|protector| protector.failures.load(std::sync::atomic::Ordering::Relaxed)).unwrap_or(0),
        }),
    )
}
