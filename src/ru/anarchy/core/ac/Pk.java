package ru.anarchy.core.ac;

/** Снимок входящего пакета игрока: создаётся в потоке Netty, разбирается в главном потоке. */
final class Pk {
    static final byte MOVE = 1, TICK = 2, SWING = 3, ATTACK = 4, INTERACT = 5, DIG = 6, USE_ON = 7, USE = 8,
            CLICK = 9, CLOSE = 10, SLOT = 11, INPUT = 12, TP = 13, SWAP = 14, STAB = 15, SPRINT = 16;
    static final int DIG_START = 0, DIG_ABORT = 1, DIG_STOP = 2;
    static final int CLICK_PICKUP = 0, CLICK_QUICK_MOVE = 1, CLICK_SWAP = 2;
    static final int IN_FORWARD = 1, IN_BACK = 2, IN_LEFT = 4, IN_RIGHT = 8, IN_JUMP = 16, IN_SHIFT = 32, IN_SPRINT = 64;
    static final int IN_MOVE = IN_FORWARD | IN_BACK | IN_LEFT | IN_RIGHT | IN_JUMP;

    final byte type;
    final long nanos;
    float yaw, pitch;
    double x, y, z;
    boolean pos, rot, ground;
    int a, b, c, d;

    Pk(byte type, long nanos) { this.type = type; this.nanos = nanos; }
}
