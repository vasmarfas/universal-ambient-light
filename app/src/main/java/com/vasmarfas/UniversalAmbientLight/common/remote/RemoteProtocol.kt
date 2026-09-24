package com.vasmarfas.UniversalAmbientLight.common.remote

/**
 * Протокол управления телевизором с телефона.
 *
 * Поверх TCP идут кадры с длиной впереди ([FrameIO]); после рукопожатия ([Handshake]) каждый
 * кадр зашифрован ([SessionCipher]) и несёт JSON. Запрос телефона — `{"id", "op", ...}`,
 * ответ ТВ — `{"re": id, "ok", ...}`, событие ТВ без запроса — `{"ev", ...}`.
 *
 * [VERSION] меняется только при несовместимых изменениях: ТВ и телефон обновляются
 * независимо, и при расхождении телефон должен честно попросить обновить приложение.
 */
object RemoteProtocol {
    const val VERSION = 1

    const val SERVICE_TYPE = "_uamblight._tcp"
    const val DEFAULT_PORT = 38911

    const val URI_SCHEME = "uamblight"
    const val URI_HOST = "pair"

    const val OP_HELLO = "hello"
    const val OP_SNAPSHOT = "snapshot"
    const val OP_SET_PREFS = "set_prefs"
    const val OP_CAPTURE = "capture"
    const val OP_CLEAR = "clear"
    const val OP_DETECT_FRAME = "detect_frame"
    const val OP_ADB = "adb"
    const val OP_DEBUG_INFO = "debug_info"
    const val OP_PING = "ping"

    const val EVENT_STATUS = "status"
    const val EVENT_PREFS = "prefs"

    const val CAPTURE_START = "start"
    const val CAPTURE_STOP = "stop"

    const val ADB_STATUS = "status"
    const val ADB_TEST = "test"
    const val ADB_LEGACY = "legacy"
    const val ADB_PAIR = "pair"
    const val ADB_AUTOPAIR = "autopair"
    const val ADB_GRANT = "grant"
    const val ADB_OPEN_DEV = "open_dev"
    const val ADB_OPEN_WIRELESS = "open_wireless"
    const val ADB_OPEN_ABOUT = "open_about"

    const val ERR_BAD_REQUEST = "bad_request"
    const val ERR_FAILED = "failed"

    /** Сколько телефонов держит ТВ одновременно; больше — признак ошибки, а не сценарий. */
    const val MAX_CLIENTS = 4

    const val PING_INTERVAL_MS = 15_000L

    /** ТВ рвёт соединение, если за это время не пришло ни одного кадра, даже пинга. */
    const val IDLE_TIMEOUT_MS = 60_000

    const val HANDSHAKE_TIMEOUT_MS = 10_000
}
