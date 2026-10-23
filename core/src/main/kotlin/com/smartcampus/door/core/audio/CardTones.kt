package com.smartcampus.door.core.audio

/** The three sounds of a card read, so a person who is not looking at the screen knows what happened. */
enum class CardTone {
    /** The antenna read a card. */
    READ,

    /** The server accepted it. */
    GRANTED,

    /** The server refused it, or could not be asked. */
    DENIED,
}

/** Plays a [CardTone] at [level] (0..1). Platform side: the controller decides when, and how loud. */
interface CardTones {
    fun play(tone: CardTone, level: Float)
}

object NoTones : CardTones {
    override fun play(tone: CardTone, level: Float) = Unit
}
