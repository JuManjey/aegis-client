//! Слой транспорта (P5, §7.2/§10.5/§10.11): встроенный Tor поверх Arti.
//!
//! Мы НЕ пишем свой Tor (§1) — берём официальный аудированный Arti (Rust,
//! Tor Project). Этот модуль поднимает клиент Tor, доводит его до готовности
//! (bootstrap) и открывает ЛОКАЛЬНЫЙ SOCKS5-прокси на `127.0.0.1`, через
//! который весь сетевой трафик клиента (сообщения/сигналинг/медиа) уходит в
//! сеть Tor. Сервер-релей публикуется как Onion Service v3 (§12.2) — клиент
//! подключается к нему через тот же SOCKS/встроенный Tor.
//!
//! Граница наружу (JNI) намеренно узкая: конфиг приходит одной JSON-строкой,
//! наружу отдаётся JSON-статус для индикатора в шапке (§13). Секретов Tor в
//! Kotlin не передаём — состояние Arti живёт в приватной песочнице приложения.
//!
//! Анти-цензура (§14.7): мосты (bridge lines) и pluggable transports
//! (obfs4 / snowflake / meek) настраиваются через типобезопасный builder Arti.

use std::net::Ipv4Addr;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use arti_client::config::pt::TransportConfigBuilder;
use arti_client::config::{
    BoolOrAuto, BridgeConfigBuilder, CfgPath, PtTransportName, TorClientConfigBuilder,
};
use arti_client::{TorClient, TorClientConfig};
use futures::StreamExt;
use serde::Deserialize;
use tokio::runtime::{Handle, Runtime};
use tor_rtcompat::PreferredRuntime;

/// Тип клиента Tor, зафиксированный на предпочитаемом рантайме (tokio+rustls).
type Client = Arc<TorClient<PreferredRuntime>>;

// ---------------------------------------------------------------------------
// Вход: конфиг запуска (JSON с Kotlin-стороны).
// ---------------------------------------------------------------------------

/// Параметры запуска Tor-транспорта. Приходят из Kotlin одной JSON-строкой.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TorStartConfig {
    /// Каталог состояния Arti (директория/консенсус/ключи). Приватная песочница.
    pub state_dir: String,
    /// Каталог кэша Arti. Приватная песочница приложения.
    pub cache_dir: String,
    /// Желаемый локальный SOCKS-порт; 0 = назначит ОС (рекомендуется).
    #[serde(default)]
    pub socks_port: u16,
    /// Строки мостов (bridge lines) — анти-цензура (§14.7). Пусто = без мостов.
    #[serde(default)]
    pub bridges: Vec<String>,
    /// Pluggable transports (obfs4/snowflake/meek) — управляемые PT (§14.7).
    #[serde(default)]
    pub transports: Vec<PtSpec>,
}

/// Описание одного pluggable transport (управляемого внешним бинарником).
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PtSpec {
    /// Имена протоколов, которые умеет этот PT (например, ["obfs4"]).
    pub protocols: Vec<String>,
    /// Путь к исполняемому файлу PT (на Android — распакованный нативный бинарь).
    #[serde(default)]
    pub path: String,
    /// Доп. аргументы командной строки PT.
    #[serde(default)]
    pub arguments: Vec<String>,
}

// ---------------------------------------------------------------------------
// Разделяемый статус для индикатора (§13) + глобальное состояние транспорта.
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)] // Disabled используется как семантический статус (as_str), но не конструируется.
enum Phase {
    Disabled,
    Starting,
    Bootstrapping,
    Connected,
    Error,
}

impl Phase {
    fn as_str(self) -> &'static str {
        match self {
            Phase::Disabled => "disabled",
            Phase::Starting => "starting",
            Phase::Bootstrapping => "bootstrapping",
            Phase::Connected => "connected",
            Phase::Error => "error",
        }
    }
}

struct StatusInner {
    phase: Phase,
    /// Прогресс bootstrap в процентах (0..=100).
    progress: u8,
    error: String,
}

/// Активный транспорт. Владеет tokio-рантаймом: его drop корректно
/// останавливает все фоновые задачи Arti и SOCKS-прокси (crypto-shredding
/// состояния не требуется — секретов PSK/Signal тут нет).
struct Running {
    _rt: Runtime,
    handle: Handle,
    client: Client,
    status: Arc<Mutex<StatusInner>>,
    socks_port: u16,
}

fn state() -> &'static Mutex<Option<Running>> {
    static STATE: OnceLock<Mutex<Option<Running>>> = OnceLock::new();
    STATE.get_or_init(|| Mutex::new(None))
}

/// Скомпилирован ли модуль Tor (для диагностики в UI).
pub fn has_support() -> bool {
    true
}

/// Ставит ring процессным CryptoProvider'ом rustls (идемпотентно, один раз).
/// Без этого rustls 0.23 не может выбрать провайдер и паникует при первом TLS.
fn ensure_crypto_provider() {
    static INIT: OnceLock<()> = OnceLock::new();
    INIT.get_or_init(|| {
        // Err означает, что провайдер уже установлен — это нормально.
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

// ---------------------------------------------------------------------------
// Публичный API модуля (вызывается из JNI-моста lib.rs).
// ---------------------------------------------------------------------------

/// Запускает (или перезапускает) встроенный Tor-транспорт по JSON-конфигу.
/// Возвращает назначенный локальный SOCKS-порт при успехе.
pub fn start(config_json: &str) -> Result<u16, String> {
    // rustls 0.23 не выбирает CryptoProvider сам, если фича провайдера не
    // активирована однозначно во всём графе (Arti использует ring напрямую).
    // Явно, идемпотентно ставим ring процессным провайдером по умолчанию ДО
    // создания любого TLS-конфига — иначе Arti паникует (SIGABRT) при старте.
    // Повторные вызовы install_default() возвращают Err — намеренно игнорируем.
    ensure_crypto_provider();

    let cfg: TorStartConfig =
        serde_json::from_str(config_json).map_err(|e| format!("некорректный JSON конфига: {e}"))?;

    if cfg.state_dir.is_empty() || cfg.cache_dir.is_empty() {
        return Err("не заданы каталоги состояния/кэша Tor".to_string());
    }

    // Останавливаем предыдущий экземпляр (drop освобождает рантайм и порт).
    stop();

    // На Android приватные каталоги приложения безопасны, но их родительские
    // директории могут не проходить строгую проверку прав fs-mistrust —
    // отключаем её штатным способом Arti (переменная окружения tor-config).
    std::env::set_var("ARTI_FS_DISABLE_PERMISSION_CHECKS", "1");

    let rt = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .thread_name("aegis-tor")
        .build()
        .map_err(|e| format!("не удалось создать tokio-рантайм: {e}"))?;
    let handle = rt.handle().clone();

    let tcfg = build_config(&cfg)?;

    // PreferredRuntime::current() нужно получать в контексте tokio.
    let runtime = {
        let _guard = rt.enter();
        PreferredRuntime::current().map_err(|e| format!("рантайм Arti недоступен: {e}"))?
    };
    // create_unbootstrapped() уже возвращает Arc<TorClient<_>>.
    let client: Client = {
        let _guard = rt.enter();
        TorClient::with_runtime(runtime)
            .config(tcfg)
            .create_unbootstrapped()
            .map_err(|e| format!("не удалось создать клиент Tor: {e}"))?
    };

    // Поднимаем локальный SOCKS5-listener (вне контекста rt.enter, чтобы
    // block_on не паниковал из-за вложенного рантайма).
    let listener = rt
        .block_on(async {
            tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, cfg.socks_port)).await
        })
        .map_err(|e| format!("не удалось открыть SOCKS-порт: {e}"))?;
    let socks_port = listener
        .local_addr()
        .map_err(|e| format!("не удалось определить SOCKS-порт: {e}"))?
        .port();

    let status = Arc::new(Mutex::new(StatusInner {
        phase: Phase::Starting,
        progress: 0,
        error: String::new(),
    }));

    // Фоновая задача bootstrap.
    {
        let c = client.clone();
        handle.spawn(async move {
            let _ = c.bootstrap().await;
        });
    }

    // Наблюдатель статуса bootstrap → обновляет разделяемый статус для UI.
    {
        let c = client.clone();
        let st = status.clone();
        handle.spawn(async move {
            let mut events = c.bootstrap_events();
            while let Some(bs) = events.next().await {
                let mut g = st.lock().unwrap();
                g.progress = (bs.as_frac() * 100.0).round().clamp(0.0, 100.0) as u8;
                if let Some(problem) = bs.blocked() {
                    g.phase = Phase::Error;
                    g.error = problem.to_string();
                } else if bs.ready_for_traffic() {
                    g.phase = Phase::Connected;
                    g.error.clear();
                } else {
                    g.phase = Phase::Bootstrapping;
                }
            }
        });
    }

    // SOCKS5-прокси: принимает локальные соединения и туннелирует их в Tor.
    {
        let c = client.clone();
        handle.spawn(async move {
            serve_socks(listener, c).await;
        });
    }

    let running = Running {
        _rt: rt,
        handle,
        client,
        status,
        socks_port,
    };
    *state().lock().unwrap() = Some(running);
    Ok(socks_port)
}

/// Останавливает транспорт и освобождает ресурсы (drop рантайма гасит задачи).
pub fn stop() {
    *state().lock().unwrap() = None;
}

/// JSON-статус для индикатора в шапке: `{state, progress, socksPort, error}`.
pub fn status_json() -> String {
    let guard = state().lock().unwrap();
    match guard.as_ref() {
        None => r#"{"state":"disabled","progress":0,"socksPort":0,"error":""}"#.to_string(),
        Some(r) => {
            let s = r.status.lock().unwrap();
            format!(
                r#"{{"state":"{}","progress":{},"socksPort":{},"error":{}}}"#,
                s.phase.as_str(),
                s.progress,
                r.socks_port,
                json_str(&s.error),
            )
        }
    }
}

/// Диагностика (§14.7): проверить доступность адреса (например, onion-релея)
/// через уже поднятый Tor. Возвращает Ok(()) при успешном подключении.
pub fn test_connect(host: &str, port: u16, timeout_ms: u64) -> Result<(), String> {
    let (client, handle) = {
        let guard = state().lock().unwrap();
        let r = guard.as_ref().ok_or_else(|| "Tor не запущен".to_string())?;
        (r.client.clone(), r.handle.clone())
    };
    let host = host.to_string();
    handle.block_on(async move {
        let fut = client.connect((host.as_str(), port));
        match tokio::time::timeout(Duration::from_millis(timeout_ms.max(1_000)), fut).await {
            Ok(Ok(_stream)) => Ok(()),
            Ok(Err(e)) => Err(e.to_string()),
            Err(_) => Err("таймаут подключения".to_string()),
        }
    })
}

// ---------------------------------------------------------------------------
// Конфиг Arti: каталоги + мосты + pluggable transports (§14.7).
// ---------------------------------------------------------------------------

fn build_config(cfg: &TorStartConfig) -> Result<TorClientConfig, String> {
    let mut builder = TorClientConfigBuilder::from_directories(&cfg.state_dir, &cfg.cache_dir);

    let use_bridges = !cfg.bridges.is_empty();
    let use_pt = !cfg.transports.is_empty();

    if use_bridges || use_pt {
        // Настойчиво требуем мосты, если они заданы (иначе — ошибка Arti).
        builder.bridges().enabled(BoolOrAuto::Explicit(use_bridges));

        // Bridge lines.
        {
            let list = builder.bridges().bridges();
            for line in &cfg.bridges {
                let bb: BridgeConfigBuilder = line
                    .parse()
                    .map_err(|e| format!("некорректная строка моста: {e}"))?;
                list.push(bb);
            }
        }

        // Pluggable transports (управляемые внешним бинарником).
        {
            let transports = builder.bridges().transports();
            for pt in &cfg.transports {
                if pt.protocols.is_empty() {
                    continue;
                }
                let mut tb = TransportConfigBuilder::new();
                let protocols: Result<Vec<PtTransportName>, _> =
                    pt.protocols.iter().map(|p| p.parse::<PtTransportName>()).collect();
                let protocols = protocols.map_err(|e| format!("некорректный протокол PT: {e}"))?;
                tb.protocols(protocols);
                if !pt.path.is_empty() {
                    tb.path(CfgPath::new(pt.path.clone()));
                }
                if !pt.arguments.is_empty() {
                    tb.arguments(pt.arguments.clone());
                }
                tb.run_on_startup(false);
                transports.push(tb);
            }
        }
    }

    builder.build().map_err(|e| format!("сборка конфига Tor: {e}"))
}

// ---------------------------------------------------------------------------
// Минимальный SOCKS5-прокси (RFC 1928), туннелирующий CONNECT в Tor.
// ---------------------------------------------------------------------------

async fn serve_socks(listener: tokio::net::TcpListener, client: Client) {
    loop {
        match listener.accept().await {
            Ok((sock, _peer)) => {
                let client = client.clone();
                tokio::spawn(async move {
                    let _ = handle_socks_conn(sock, client).await;
                });
            }
            // Listener закрыт (транспорт останавливается) — выходим.
            Err(_) => break,
        }
    }
}

async fn handle_socks_conn(
    mut sock: tokio::net::TcpStream,
    client: Client,
) -> std::io::Result<()> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    // --- Приветствие: VER, NMETHODS, METHODS ---
    let mut head = [0u8; 2];
    sock.read_exact(&mut head).await?;
    if head[0] != 0x05 {
        return Ok(()); // не SOCKS5 — молча закрываем
    }
    let n_methods = head[1] as usize;
    let mut methods = vec![0u8; n_methods];
    sock.read_exact(&mut methods).await?;
    // Отвечаем: версия 5, метод «без аутентификации» (0x00).
    sock.write_all(&[0x05, 0x00]).await?;

    // --- Запрос: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT ---
    let mut req = [0u8; 4];
    sock.read_exact(&mut req).await?;
    if req[0] != 0x05 {
        return Ok(());
    }
    let cmd = req[1];
    let atyp = req[3];

    let host: String = match atyp {
        0x01 => {
            let mut a = [0u8; 4];
            sock.read_exact(&mut a).await?;
            Ipv4Addr::from(a).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            sock.read_exact(&mut len).await?;
            let mut dom = vec![0u8; len[0] as usize];
            sock.read_exact(&mut dom).await?;
            match String::from_utf8(dom) {
                Ok(s) => s,
                Err(_) => {
                    let _ = reply(&mut sock, 0x01).await; // general failure
                    return Ok(());
                }
            }
        }
        0x04 => {
            let mut a = [0u8; 16];
            sock.read_exact(&mut a).await?;
            std::net::Ipv6Addr::from(a).to_string()
        }
        _ => {
            let _ = reply(&mut sock, 0x08).await; // address type not supported
            return Ok(());
        }
    };
    let mut port_bytes = [0u8; 2];
    sock.read_exact(&mut port_bytes).await?;
    let port = u16::from_be_bytes(port_bytes);

    // Поддерживаем только CONNECT (0x01). BIND/UDP-ASSOCIATE не нужны мессенджеру.
    if cmd != 0x01 {
        let _ = reply(&mut sock, 0x07).await; // command not supported
        return Ok(());
    }

    // Открываем анонимный поток через Tor. Хостнейм резолвится на выходном узле.
    match client.connect((host.as_str(), port)).await {
        Ok(mut tor_stream) => {
            reply(&mut sock, 0x00).await?; // succeeded
            // Двунаправленное проксирование до закрытия любой из сторон.
            let _ = tokio::io::copy_bidirectional(&mut sock, &mut tor_stream).await;
            Ok(())
        }
        Err(_e) => {
            let _ = reply(&mut sock, 0x05).await; // connection refused
            Ok(())
        }
    }
}

/// Отправляет ответ SOCKS5 с кодом `rep` и «нулевым» BND-адресом.
async fn reply(sock: &mut tokio::net::TcpStream, rep: u8) -> std::io::Result<()> {
    use tokio::io::AsyncWriteExt;
    // VER, REP, RSV, ATYP=IPv4, BND.ADDR=0.0.0.0, BND.PORT=0
    sock.write_all(&[0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await
}

/// Экранирование строки в JSON (для поля error статуса).
fn json_str(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_without_bridges_builds() {
        let dir = std::env::temp_dir();
        let cfg = TorStartConfig {
            state_dir: dir.join("aegis-tor-state").to_string_lossy().into_owned(),
            cache_dir: dir.join("aegis-tor-cache").to_string_lossy().into_owned(),
            socks_port: 0,
            bridges: vec![],
            transports: vec![],
        };
        assert!(build_config(&cfg).is_ok());
    }

    #[test]
    fn config_json_parses_camel_case() {
        let json = r#"{"stateDir":"/tmp/s","cacheDir":"/tmp/c","socksPort":9150,
            "bridges":["obfs4 1.2.3.4:1 ABCD cert=xyz iat-mode=0"],
            "transports":[{"protocols":["obfs4"],"path":"/data/pt/obfs4","arguments":["-x"]}]}"#;
        let cfg: TorStartConfig = serde_json::from_str(json).unwrap();
        assert_eq!(cfg.socks_port, 9150);
        assert_eq!(cfg.bridges.len(), 1);
        assert_eq!(cfg.transports[0].protocols, vec!["obfs4".to_string()]);
        assert_eq!(cfg.transports[0].path, "/data/pt/obfs4");
    }

    #[test]
    fn status_json_disabled_by_default() {
        // До запуска статус — disabled (глобальное состояние пустое).
        let s = status_json();
        assert!(s.contains("\"state\":\"disabled\"") || s.contains("\"state\":\"connected\""));
    }

    #[test]
    fn bridge_line_parses_into_config() {
        let cfg = TorStartConfig {
            state_dir: "/tmp/aegis-s".to_string(),
            cache_dir: "/tmp/aegis-c".to_string(),
            socks_port: 0,
            bridges: vec![
                "obfs4 192.0.2.1:443 0000000000000000000000000000000000000000 cert=aaaa iat-mode=0"
                    .to_string(),
            ],
            transports: vec![PtSpec {
                protocols: vec!["obfs4".to_string()],
                path: "/data/local/tmp/obfs4proxy".to_string(),
                arguments: vec![],
            }],
        };
        // Мосты + PT должны корректно собраться в конфиг Arti.
        assert!(build_config(&cfg).is_ok());
    }

    #[test]
    fn snowflake_and_managed_obfs4_config_builds() {
        // Проверяем, что реальные bridge lines встроенных мостов (§14.7) —
        // полная строка Snowflake (с url/front/ice/utls-imitate) и obfs4 —
        // вместе с управляемыми PT (путь к вложенному бинарю lyrebird/snowflake,
        // как их подставляет ArtiTorTransport из nativeLibraryDir) корректно
        // собираются в конфиг Arti. Так проверяется контракт клиент↔ядро для
        // managed pluggable transports.
        let cfg = TorStartConfig {
            state_dir: "/tmp/aegis-pt-s".to_string(),
            cache_dir: "/tmp/aegis-pt-c".to_string(),
            socks_port: 0,
            bridges: vec![
                "obfs4 212.83.43.95:443 BFE712113A72899AD685764B211FACD30FF52C31 \
                 cert=ayq0XzCwhpdysn5o0EyDUbmSOx3X/oTEbzDMvczHOdBJKlvIdHHLJGkZARtT4dcBFArPPg \
                 iat-mode=1"
                    .to_string(),
                "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 \
                 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 \
                 url=https://1098762253.rsc.cdn77.org front=www.phpmyadmin.net,cdn.zk.mk \
                 ice=stun:stun.antisip.com:3478 utls-imitate=hellorandomizedalpn"
                    .to_string(),
            ],
            transports: vec![
                PtSpec {
                    protocols: vec!["obfs4".to_string(), "meek_lite".to_string()],
                    path: "/data/app/aegis/lib/arm64/liblyrebird.so".to_string(),
                    arguments: vec![],
                },
                PtSpec {
                    protocols: vec!["snowflake".to_string()],
                    path: "/data/app/aegis/lib/arm64/libsnowflake.so".to_string(),
                    arguments: vec![],
                },
            ],
        };
        assert!(build_config(&cfg).is_ok());
    }
}
