package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Prioridade do tráfego (§2.1). [HIGH] consome agressivamente via lease (§5); [LOW] passa pelo
 * portão de pacing — só é admitida abaixo da linha de liberação (§6).
 */
enum class Priority { HIGH, LOW }
