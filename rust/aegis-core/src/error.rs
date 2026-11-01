//! Единый тип ошибки нативного ядра AEGIS.

use std::fmt;

#[derive(Debug)]
pub enum AegisError {
    /// Некорректный аргумент от вызывающей стороны (Kotlin).
    InvalidArg(String),
    /// Ошибка криптографической операции (провал тега AEAD, KDF и т.п.).
    Crypto(String),
}

impl fmt::Display for AegisError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            AegisError::InvalidArg(m) => write!(f, "invalid argument: {m}"),
            AegisError::Crypto(m) => write!(f, "crypto error: {m}"),
        }
    }
}

impl std::error::Error for AegisError {}
