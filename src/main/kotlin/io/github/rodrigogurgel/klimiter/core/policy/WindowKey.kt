package io.github.rodrigogurgel.klimiter.core.policy

import io.github.rodrigogurgel.klimiter.core.domain.Dimension
import io.github.rodrigogurgel.klimiter.core.domain.DimensionValue
import io.github.rodrigogurgel.klimiter.core.domain.Window

/**
 * Ponto único de derivação da janela e da **chave determinística** (§3.2, ARQUITETURA.md §6): todos
 * os nós, para a mesma janela, produzem a mesma chave — invariante da coordenação distribuída. Esse
 * cálculo não se espalha por adapters nem se duplica.
 */
object WindowKey {
    /** Janela vigente para [nowEpochSecond] segundo a unit da [policy] (§3.1/§3.2). */
    fun window(policy: Policy, nowEpochSecond: Long): Window = Window.derive(nowEpochSecond, policy.unit.window)

    /** Chave da janela: `prefixo:dimensão:valor:início` (§3.2). */
    fun key(prefix: String, dimension: Dimension, value: DimensionValue, window: Window): String =
        "$prefix:${dimension.raw}:${value.raw}:${window.startEpochSecond}"
}
