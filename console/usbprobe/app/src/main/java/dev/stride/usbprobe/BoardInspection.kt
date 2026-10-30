package dev.stride.usbprobe

/** Small bounded query plan. Unsupported features are skipped; failures abort without retries. */
class BoardInspection(
    private val exchange: (BoardReads.Query) -> BoardReads.Reply,
    private val report: (String) -> Unit,
) {
    fun run() {
        val root = readIdentity(2)
        val commands = BoardReads.commands(exchange(BoardReads.Query.Information(root.device, BoardReads.Info.COMMANDS)))
        report("Advertised optional commands: ${commands.sorted()}")
        if (130 in commands) report(BoardReads.system(exchange(BoardReads.Query.Information(2, BoardReads.Info.SYSTEM))))
        if (132 in commands) report(BoardReads.version(exchange(BoardReads.Query.Information(2, BoardReads.Info.VERSION))))
        val children = if (root.device == 2 || 128 in commands) {
            BoardReads.devices(exchange(BoardReads.Query.Information(root.device, BoardReads.Info.DEVICES)))
                .also { report("Advertised devices: $it") }
        } else emptyList()
        val target = when {
            root.device in setOf(4, 5) -> root
            4 in children -> readIdentity(4)
            5 in children -> readIdentity(5)
            else -> root
        }
        // iFit's QueueManager treats ReadWriteData (2) as a base command, independent
        // of SupportedCommands' optional list. This path always has an empty write mask.
        report("Base read/status query; write section remains empty.")
        val fields = BoardReads.Field.values().filter { it.id in target.fields }
        if (fields.isEmpty()) { report("No selected status fields advertised; stopping."); return }
        val query = BoardReads.Query.Values(target.device, fields)
        val values = BoardReads.values(query, exchange(query))
        values.forEach { (field, value) -> report("${field.name}=${value ?: "unavailable (FF fill)"}") }
        report("DONE: one status snapshot; no control writes, unlock, connect or reset.")
    }

    private fun readIdentity(device: Int): BoardReads.Identity {
        val result = BoardReads.identity(exchange(BoardReads.Query.Information(device, BoardReads.Info.IDENTITY)))
        report("Identity: address=${result.device} software=${result.software} hardware=${result.hardware} manufacturer=${result.manufacturer}")
        report("Supported fields: ${result.fields.sorted()}")
        return result
    }
}
