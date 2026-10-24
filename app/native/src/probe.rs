//! 仅同步设置调用方持有的 ICMP socket 为非阻塞；不借用 TUN、不保护或改变路由。

pub fn nonblocking(fd: i32) -> Result<(), &'static str> {
    if fd < 0 {
        return Err("invalid_fd");
    }
    // fd 由 Kotlin 的临时 dup 保持有效直到同步 JNI 返回；本函数不取得关闭所有权。
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags < 0 || unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
        Err("probe_socket_unavailable")
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::AsRawFd;

    /// 对实际宿主 datagram 设置非阻塞后，空读取立即返回 WouldBlock；函数不关闭调用方句柄。
    #[test]
    fn nonblocking_preserves_ownership_and_never_waits_for_data() {
        let (socket, peer) = std::os::unix::net::UnixDatagram::pair().unwrap();
        nonblocking(socket.as_raw_fd()).unwrap();
        assert_eq!(
            socket.recv(&mut [0; 8]).unwrap_err().kind(),
            std::io::ErrorKind::WouldBlock
        );
        peer.send(b"ping").unwrap();
        let mut buf = [0; 8];
        assert_eq!(socket.recv(&mut buf).unwrap(), 4);
        assert_eq!(&buf[..4], b"ping");
        assert!(nonblocking(-1).is_err());
    }
}
