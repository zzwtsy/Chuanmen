package com.github.zzwtsy.chuanmen.lib.profile

import java.util.Collections

/** 防止 UI、调用方或历史快照持有的可变集合改写已经提交的修订。 */
internal fun SavedRevision.frozen(): SavedRevision = copy(
    vpnPolicy = vpnPolicy.copy(dnsServers = Collections.unmodifiableList(vpnPolicy.dnsServers.toList())),
)
