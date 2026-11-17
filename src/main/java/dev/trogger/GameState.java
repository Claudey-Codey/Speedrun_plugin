package dev.trogger;

public enum GameState {
    /** Waiting for 2 players and for someone to pick a seed. The whole server is tick-frozen. */
    LOBBY,
    /** A seed was picked and the worlds are being generated. */
    GENERATING,
    /** Players are in their worlds; 10 second countdown, still frozen. */
    COUNTDOWN,
    /** Gathering gear. */
    GATHER,
    /** Fighting in the arena. */
    FIGHT,
    /** Someone won; returning to the lobby soon. */
    ENDED
}
