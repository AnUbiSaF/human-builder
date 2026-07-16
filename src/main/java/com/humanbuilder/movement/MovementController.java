package com.humanbuilder.movement;

import com.humanbuilder.camera.CameraSmoother;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Контроллер движения персонажа.
 *
 * Управляет ходьбой (WASD), прыжками и остановкой.
 * Работает в связке с CameraSmoother: во время ходьбы
 * камера направлена в сторону движения (как у реального игрока),
 * а при остановке — переводится на целевой блок.
 *
 * Движение реализовано через нажатие клавиш (options.forwardKey и т.д.),
 * а не телепортацию — это выглядит полностью легитимно.
 */
public class MovementController {

    private final MinecraftClient client;
    private final CameraSmoother camera;

    /** Максимальная дальность установки блока (vanilla survival = 4.5) */
    private static final double REACH_DISTANCE = 4.3;

    /** Расстояние, на котором считаем «пришли» */
    private static final double ARRIVAL_THRESHOLD = 2.8;

    /** Цель, к которой идём */
    private BlockPos targetPos;

    /** Активен ли контроллер */
    private boolean active = false;

    /** Тиков с момента начала ходьбы (для детекции застревания) */
    private int walkTicks = 0;

    /** Позиция на прошлом тике (для детекции застревания) */
    private Vec3d lastPos = Vec3d.ZERO;

    /** Тиков без движения */
    private int stuckTicks = 0;

    public MovementController(MinecraftClient client, CameraSmoother camera) {
        this.client = client;
        this.camera = camera;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Публичный API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Начать ходьбу к позиции.
     */
    public void walkTo(BlockPos target) {
        this.targetPos = target;
        this.active = true;
        this.walkTicks = 0;
        this.stuckTicks = 0;
        if (client.player != null) {
            this.lastPos = client.player.getPos();
        }
    }

    /**
     * Вызывается каждый тик. Управляет клавишами движения.
     */
    public void tick() {
        if (!active || client.player == null || targetPos == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        Vec3d playerPos = player.getPos();
        Vec3d targetVec = Vec3d.ofCenter(targetPos);

        double dx = targetVec.x - playerPos.x;
        double dz = targetVec.z - playerPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        walkTicks++;

        // ── Проверяем, пришли ли ──────────────────────────────────────
        if (horizontalDist < ARRIVAL_THRESHOLD && isWithinReach(targetPos)) {
            if (player.getAbilities().creativeMode && player.getAbilities().flying) {
                player.getAbilities().flying = false;
                player.sendAbilitiesUpdate();
            }
            stop();
            return;
        }

        // ── Адаптивный креативный полет ──────────────────────────────
        double dy = targetVec.y - playerPos.y;
        boolean needsFlight = false;

        // Если цель существенно выше ног игрока (1.5+ блоков) или ниже (2.5+ блоков)
        if (dy > 1.5 || dy < -2.5) {
            needsFlight = true;
        }
        // Если застряли во время ходьбы на 30+ тиков (1.5 секунды)
        if (stuckTicks > 30) {
            needsFlight = true;
        }

        if (player.getAbilities().creativeMode) {
            boolean isFlying = player.getAbilities().flying;
            if (needsFlight && !isFlying) {
                player.getAbilities().flying = true;
                player.sendAbilitiesUpdate();
            } else if (!needsFlight && isFlying) {
                // Выключаем полет, если вернулись на целевую высоту
                if (player.isOnGround() || Math.abs(dy) <= 1.0) {
                    player.getAbilities().flying = false;
                    player.sendAbilitiesUpdate();
                }
            }
        }

        // ── Направляем камеру в сторону движения (только если мы не вплотную) ──
        float moveYaw;
        if (horizontalDist > 0.2) {
            moveYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            camera.lookAt(
                    playerPos.x + dx * 0.5,
                    playerPos.y + player.getStandingEyeHeight(),
                    playerPos.z + dz * 0.5
            );
            camera.snapToTarget(); // Мгновенный разворот головы при беге
        } else {
            moveYaw = player.getYaw();
        }

        // ── Определяем, какие клавиши нажимать ───────────────────────
        //    Вычисляем угол между нашим взглядом и направлением к цели,
        //    и нажимаем соответствующую комбинацию WASD.
        float currentYaw = player.getYaw();
        float deltaYaw = MathHelper.wrapDegrees(moveYaw - currentYaw);

        releaseAllKeys();

        if (Math.abs(deltaYaw) < 40) {
            client.options.forwardKey.setPressed(true);

            // Автоматический спринт при беге на ногах
            if (!player.getAbilities().flying) {
                player.setSprinting(true);
            }
        }

        // ── Управление высотой при полете ─────────────────────────────
        if (player.getAbilities().flying) {
            if (dy > 0.5) {
                client.options.jumpKey.setPressed(true); // лететь вверх
            } else if (dy < -0.5) {
                client.options.sneakKey.setPressed(true); // лететь вниз
            }
        } else {
            // Обычный авто-прыжок на земле
            if (player.horizontalCollision && player.isOnGround()) {
                client.options.jumpKey.setPressed(true);
            }
        }

        // ── Детекция застревания ──────────────────────────────────────
        if (walkTicks % 10 == 0) {
            double moved = playerPos.squaredDistanceTo(lastPos);
            if (moved < 0.01) {
                stuckTicks += 10;
            } else {
                stuckTicks = 0;
            }
            lastPos = playerPos;
        }
    }

    /**
     * Проверяет, находится ли позиция в пределах досягаемости.
     */
    public boolean isWithinReach(BlockPos pos) {
        if (client.player == null) return false;
        Vec3d eyePos = client.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        return eyePos.distanceTo(target) <= REACH_DISTANCE;
    }

    /**
     * Проверяет, пришёл ли игрок к цели.
     */
    public boolean hasArrived() {
        if (!active || client.player == null || targetPos == null) return false;
        return isWithinReach(targetPos);
    }

    /**
     * Остановить движение и отпустить все клавиши.
     */
    public void stop() {
        active = false;
        targetPos = null;
        walkTicks = 0;
        stuckTicks = 0;
        releaseAllKeys();
    }

    public boolean isActive() {
        return active;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Внутренние методы
    // ════════════════════════════════════════════════════════════════════

    private void releaseAllKeys() {
        if (client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }
}
