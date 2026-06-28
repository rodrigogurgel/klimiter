package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Capacidade restante estimada na janela, ecoada ao cliente (§2.1): `0` quando negado ou em
 * pass-through. Value Object com invariante de não-negatividade (R5 da ARQUITETURA.md).
 *
 * Em [Long] (o proto trafega `uint64`) porque deriva do contador da janela e do livre global (§4),
 * que trabalham em [Long]; a conversão para o proto acontece na borda gRPC.
 */
@JvmInline
value class Remaining(val value: Long) {
    init {
        require(value >= 0) { "remaining deve ser >= 0, recebido $value" }
    }

    companion object {
        /** Sem capacidade restante a reportar: negação ou pass-through (§2.1). */
        val NONE = Remaining(0)
    }
}
