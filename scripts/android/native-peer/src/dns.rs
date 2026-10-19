//! 只解析本轮公开合成域名；事务身份与题目均保留，不转发外部 DNS。
use sha2::{Digest, Sha256};

pub struct Answer {
    pub response: Vec<u8>,
    pub transaction: u16,
    pub query_type: u16,
    pub name_sha256: String,
}

pub fn answer(query: &[u8], run_id: &str) -> Option<Answer> {
    if query.len() < 17
        || query.len() > 4096
        || query[2] & 0xf8 != 0
        || u16::from_be_bytes([query[4], query[5]]) != 1
    {
        return None;
    }
    let mut at = 12;
    let mut labels = Vec::new();
    loop {
        let length = *query.get(at)? as usize;
        at += 1;
        if length == 0 {
            break;
        }
        if length > 63 {
            return None;
        }
        let part = query.get(at..at + length)?;
        if !part
            .iter()
            .all(|byte| byte.is_ascii_alphanumeric() || *byte == b'-')
        {
            return None;
        }
        labels.push(std::str::from_utf8(part).ok()?.to_ascii_lowercase());
        at += length;
        if at > 267 {
            return None;
        }
    }
    let name = labels.join(".");
    if !name.starts_with(&format!("{}-", run_id.to_ascii_lowercase()))
        || !name.ends_with(".p02.invalid")
    {
        return None;
    }
    let kind = u16::from_be_bytes(query.get(at..at + 2)?.try_into().ok()?);
    let class = u16::from_be_bytes(query.get(at + 2..at + 4)?.try_into().ok()?);
    if !matches!(kind, 1 | 28) || class != 1 {
        return None;
    }
    at += 4;
    let transaction = u16::from_be_bytes([query[0], query[1]]);
    let mut response = query[..at].to_vec();
    response[2..4].copy_from_slice(&[0x81, 0x80]);
    response[6..8].copy_from_slice(&(if kind == 1 { 1u16 } else { 0u16 }).to_be_bytes());
    response[8..12].fill(0);
    if kind == 1 {
        response.extend_from_slice(&[0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 0, 0, 4, 10, 222, 0, 1]);
    }
    Some(Answer {
        response,
        transaction,
        query_type: kind,
        name_sha256: format!("{:x}", Sha256::digest(name.as_bytes())),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn query(name: &str, kind: u16) -> Vec<u8> {
        let mut query = vec![0x01, 0x02, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0];
        for part in name.split('.') {
            query.push(part.len() as u8);
            query.extend_from_slice(part.as_bytes());
        }
        query.push(0);
        query.extend_from_slice(&kind.to_be_bytes());
        query.extend_from_slice(&[0, 1]);
        query
    }
    /// 本轮 A 查询保留事务与题目并返回固定地址；AAAA 返回明确无记录，不伪造 IPv6 能力。
    #[test]
    fn synthetic_queries_have_correlated_answers() {
        for kind in [1, 28] {
            let request = query("p02-dns-unique.p02.invalid", kind);
            let result = answer(&request, "p02-dns").unwrap();
            assert_eq!(result.transaction, 258);
            assert_eq!(result.query_type, kind);
            assert_eq!(&result.response[12..request.len()], &request[12..]);
            assert_eq!(
                u16::from_be_bytes(result.response[6..8].try_into().unwrap()),
                if kind == 1 { 1 } else { 0 }
            );
            if kind == 1 {
                assert_eq!(
                    &result.response[result.response.len() - 4..],
                    &[10, 222, 0, 1]
                );
            }
        }
    }
    /// DNS 域名不区分大小写；运行身份同样规范化，但请求题目的原始字节保持不变。
    #[test]
    fn uppercase_query_and_run_identity_are_correlated() {
        let request = query("P02-DNS-Unique.P02.Invalid", 1);
        let result = answer(&request, "p02-DNS").unwrap();
        assert_eq!(&result.response[12..request.len()], &request[12..]);
        assert_eq!(
            result.name_sha256,
            format!("{:x}", Sha256::digest(b"p02-dns-unique.p02.invalid"))
        );
    }
    /// 非本轮域名、未知问题类型、压缩环、截断或响应包均不得产生受控回答。
    #[test]
    fn unrelated_or_malformed_queries_are_rejected() {
        let valid = query("p02-dns-unique.p02.invalid", 1);
        let mut compression = valid.clone();
        compression[12] = 0xc0;
        let mut response = valid.clone();
        response[2] |= 0x80;
        for invalid in [
            query("p02-old-unique.p02.invalid", 1),
            query("example.invalid", 1),
            query("p02-dns-unique.p02.invalid", 16),
            valid[..16].to_vec(),
            compression,
            response,
        ] {
            assert!(answer(&invalid, "p02-dns").is_none());
        }
    }
}
