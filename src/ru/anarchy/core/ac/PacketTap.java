package ru.anarchy.core.ac;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Читает входящие пакеты игрока в Netty (перед обработчиком сервера) и складывает их в очередь. Пакеты не меняет. */
final class PacketTap {
    static final String NAME = "aquaguard_ai";
    private final AntiCheat ac;
    private volatile boolean broken;

    PacketTap(AntiCheat ac) { this.ac = ac; }

    boolean broken() { return broken; }

    void inject(Player p, ACPlayer d) {
        if (broken) return;
        try {
            Channel ch = channel(p);
            if (ch == null) return;
            ch.eventLoop().execute(() -> {
                try {
                    if (!ch.isOpen()) return;
                    if (ch.pipeline().get(NAME) != null) ch.pipeline().remove(NAME);
                    if (ch.pipeline().get("packet_handler") == null) return;
                    ch.pipeline().addBefore("packet_handler", NAME, new Tap(d));
                    d.tapped = true;
                } catch (Throwable t) { fail(t); }
            });
        } catch (Throwable t) { fail(t); }
    }

    void eject(Player p) {
        try {
            Channel ch = channel(p);
            if (ch == null) return;
            ch.eventLoop().execute(() -> {
                try { if (ch.pipeline().get(NAME) != null) ch.pipeline().remove(NAME); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    private static Channel channel(Player p) {
        var h = ((CraftPlayer) p).getHandle();
        return h.connection == null ? null : h.connection.connection.channel;
    }

    private void fail(Throwable t) {
        if (broken) return;
        broken = true;
        ac.warn("Перехват пакетов отключён: " + t + ". Проверки аима, ауры и кликера выключены, остальные работают.");
    }

    private final class Tap extends ChannelInboundHandlerAdapter {
        private final ACPlayer d;

        Tap(ACPlayer d) { this.d = d; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!broken) {
                try { read(msg); } catch (Throwable t) { fail(t); }
            }
            super.channelRead(ctx, msg);
        }

        private void read(Object msg) {
            long now = System.nanoTime();
            Pk k;
            if (msg instanceof ServerboundMovePlayerPacket m) {
                k = new Pk(Pk.MOVE, now);
                k.rot = m.hasRotation(); k.pos = m.hasPosition(); k.ground = m.isOnGround();
                if (k.rot) { k.yaw = m.getYRot(0f); k.pitch = m.getXRot(0f); }
                if (k.pos) { k.x = m.getX(0); k.y = m.getY(0); k.z = m.getZ(0); }
            } else if (msg instanceof ServerboundClientTickEndPacket) {
                k = new Pk(Pk.TICK, now);
            } else if (msg instanceof ServerboundSwingPacket s) {
                k = new Pk(Pk.SWING, now);
                k.a = s.getHand() == InteractionHand.MAIN_HAND ? 0 : 1;
            } else if (msg instanceof ServerboundInteractPacket i) {
                k = new Pk(i.isAttack() ? Pk.ATTACK : Pk.INTERACT, now);
                k.a = i.getEntityId();
            } else if (msg instanceof ServerboundPlayerActionPacket a) {
                switch (a.getAction().name()) {
                    case "START_DESTROY_BLOCK" -> { k = new Pk(Pk.DIG, now); k.a = Pk.DIG_START; }
                    case "ABORT_DESTROY_BLOCK" -> { k = new Pk(Pk.DIG, now); k.a = Pk.DIG_ABORT; }
                    case "STOP_DESTROY_BLOCK" -> { k = new Pk(Pk.DIG, now); k.a = Pk.DIG_STOP; }
                    case "SWAP_ITEM_WITH_OFFHAND" -> k = new Pk(Pk.SWAP, now);
                    case "STAB" -> k = new Pk(Pk.STAB, now);
                    default -> { return; }
                }
            } else if (msg instanceof ServerboundUseItemOnPacket u) {
                k = new Pk(Pk.USE_ON, now);
                k.a = u.getHand() == InteractionHand.MAIN_HAND ? 0 : 1;
                var bp = u.getHitResult().getBlockPos();
                k.b = bp.getX(); k.c = bp.getY(); k.d = bp.getZ();
            } else if (msg instanceof ServerboundUseItemPacket u) {
                k = new Pk(Pk.USE, now);
                k.a = u.getHand() == InteractionHand.MAIN_HAND ? 0 : 1;
            } else if (msg instanceof ServerboundContainerClickPacket c) {
                k = new Pk(Pk.CLICK, now);
                k.a = c.containerId(); k.b = c.slotNum(); k.c = c.buttonNum(); k.d = c.clickType().ordinal();
            } else if (msg instanceof ServerboundContainerClosePacket c) {
                k = new Pk(Pk.CLOSE, now);
                k.a = c.getContainerId();
            } else if (msg instanceof ServerboundSetCarriedItemPacket s) {
                k = new Pk(Pk.SLOT, now);
                k.a = s.getSlot();
            } else if (msg instanceof ServerboundPlayerInputPacket ip) {
                Input in = ip.input();
                k = new Pk(Pk.INPUT, now);
                k.a = (in.forward() ? Pk.IN_FORWARD : 0) | (in.backward() ? Pk.IN_BACK : 0) | (in.left() ? Pk.IN_LEFT : 0)
                        | (in.right() ? Pk.IN_RIGHT : 0) | (in.jump() ? Pk.IN_JUMP : 0) | (in.shift() ? Pk.IN_SHIFT : 0) | (in.sprint() ? Pk.IN_SPRINT : 0);
            } else if (msg instanceof ServerboundPlayerCommandPacket pc) {
                String act = pc.getAction().name();
                if (!act.equals("START_SPRINTING") && !act.equals("STOP_SPRINTING")) return;
                k = new Pk(Pk.SPRINT, now);
                k.a = act.equals("START_SPRINTING") ? 1 : 0;
            } else if (msg instanceof ServerboundAcceptTeleportationPacket) {
                k = new Pk(Pk.TP, now);
            } else return;
            d.offer(k);
        }
    }
}
