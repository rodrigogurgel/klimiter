package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Unidades que uma requisição quer consumir da janela (`N`, §2.1) — tipicamente 1; `0` = nada a
 * cobrar. Value Object com invariante de não-negatividade (R5 da ARQUITETURA.md).
 *
 * Modelado em [Long] (o proto trafega `uint32`) porque o contador da janela e a aritmética da
 * linha trabalham em [Long] (§4, §6); a conversão acontece na borda gRPC, não no núcleo.
 */
@JvmInline
value class Hits(val value: Long) {
    init {
        require(value >= 0) { "hits deve ser >= 0, recebido $value" }
    }

    /** `N ≤ 0` (§5/§6): admitido sem tocar o central. */
    val isZero: Boolean get() = value == 0L
}
