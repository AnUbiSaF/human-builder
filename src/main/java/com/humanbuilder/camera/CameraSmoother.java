package com.humanbuilder.camera;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Плавное управление камерой (Yaw/Pitch) игрока.
 *
 * Использует двухуровневую интерполяцию:
 *   — Быстрый LERP для грубого наведения (когда ошибка > 10°)
 *   — Медленный LERP для точной подстройки (когда ошибка < 10°)
 *
 * Дополнительно накладывается микро-тремор руки (±0.12°) для полной
 * имитации человека.
 *
 * Это даёт плавный, реалистичный поворот камеры без рывков.
 */
public class CameraSmoother {

    private final MinecraftClient client;
    private final Random random = new Random();

    // ── Текущее состояние ──────────────────────────────────────────────
    private float targetYaw;
    private float targetPitch;
    private float yawVelocity;
    private float pitchVelocity;

    // ── Тремор руки ────────────────────────────────────────────────────
    private float tremorYaw = 0f;
    private float tremorPitch = 0f;
    private float tremorTargetYaw = 0f;
    private float tremorTargetPitch = 0f;
    private static final float TREMOR_AMPLITUDE = 0.08f;
    private static final float TREMOR_LERP_SPEED = 0.06f;

    // ── Управление ─────────────────────────────────────────────────────
    private boolean active = false;
    private static final float CONVERGENCE_THRESHOLD = 2.0f;
    private static final float MAX_YAW_SPEED = 32.0f;
    private static final float MAX_PITCH_SPEED = 24.0f;
    private static final float YAW_ACCELERATION = 8.0f;
    private static final float PITCH_ACCELERATION = 6.5f;

    public CameraSmoother(MinecraftClient client) {
        this.client = client;
    }

    // ════════════════════════════════════════════════════════════════════
    //  Публичный API
    // ════════════════════════════════════════════════════════════════════

    /**
     * Начать плавный поворот камеры к мировым координатам.
     * Автоматически учитывает высоту глаз игрока.
     */
    public void lookAt(double x, double y, double z) {
        if (client.player == null) return;

        double dx = x - client.player.getX();
        double dy = y - client.player.getEyeY();
        double dz = z - client.player.getZ();
        double dxz = Math.sqrt(dx * dx + dz * dz);

        // atan2(dz, dx) даёт угол от оси X; Minecraft Yaw отсчитывается от -Z
        float calculatedYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        targetYaw = client.player.getYaw()
                + MathHelper.wrapDegrees(calculatedYaw - client.player.getYaw());
        targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, dxz)));
        targetPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

        active = true;
    }

    /**
     * Начать плавный поворот к вектору.
     */
    public void lookAt(Vec3d target) {
        lookAt(target.x, target.y, target.z);
    }

    /** Aims pitch at the hit point while forcing the yaw required by placement state. */
    public void lookAtWithYaw(Vec3d target, float yaw) {
        lookAt(target);
        if (client.player != null) {
            targetYaw = client.player.getYaw() + MathHelper.wrapDegrees(yaw - client.player.getYaw());
        }
    }

    /**
     * Вызывается каждый клиентский тик из TickHandler.
     * Двигает yaw/pitch игрока плавно к цели + тремор.
     */
    public void tick() {
        if (!active || client.player == null) return;
        float currentYaw = MathHelper.wrapDegrees(client.player.getYaw());
        float currentPitch = MathHelper.wrapDegrees(client.player.getPitch());
        float rawYawError = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float rawPitchError = targetPitch - currentPitch;

        // Tremor is only visible while fine-aiming, never during a large turn.
        if (Math.abs(rawYawError) + Math.abs(rawPitchError) < 5.0f) {
            updateTremor();
        } else {
            tremorYaw += (0.0f - tremorYaw) * 0.2f;
            tremorPitch += (0.0f - tremorPitch) * 0.2f;
        }

        float aimedYaw = MathHelper.wrapDegrees(targetYaw + tremorYaw);
        float aimedPitch = MathHelper.clamp(targetPitch + tremorPitch, -90.0f, 90.0f);
        float yawError = MathHelper.wrapDegrees(aimedYaw - currentYaw);
        float pitchError = aimedPitch - currentPitch;

        float desiredYawVelocity = MathHelper.clamp(yawError * 0.38f, -MAX_YAW_SPEED, MAX_YAW_SPEED);
        float desiredPitchVelocity = MathHelper.clamp(pitchError * 0.34f, -MAX_PITCH_SPEED, MAX_PITCH_SPEED);
        yawVelocity = approach(yawVelocity, desiredYawVelocity, YAW_ACCELERATION);
        pitchVelocity = approach(pitchVelocity, desiredPitchVelocity, PITCH_ACCELERATION);

        if (Math.abs(yawError) < Math.abs(yawVelocity)) yawVelocity = yawError;
        if (Math.abs(pitchError) < Math.abs(pitchVelocity)) pitchVelocity = pitchError;

        client.player.setYaw(MathHelper.wrapDegrees(currentYaw + yawVelocity));
        client.player.setPitch(MathHelper.clamp(currentPitch + pitchVelocity, -90.0f, 90.0f));
    }

    /**
     * Прицел достаточно близок к цели?
     * Используется BuildExecutor для перехода LOOKING → PLACING.
     */
    public boolean isConverged() {
        if (client.player == null) return false;
        float yawErr  = Math.abs(MathHelper.wrapDegrees(targetYaw - client.player.getYaw()));
        float pitchErr = Math.abs(targetPitch - client.player.getPitch());
        return yawErr < CONVERGENCE_THRESHOLD && pitchErr < CONVERGENCE_THRESHOLD
                && Math.abs(yawVelocity) < 1.5f && Math.abs(pitchVelocity) < 1.5f;
    }

    /** Checks the placement-facing angle without requiring an exact pitch match. */
    public boolean isYawConverged(float tolerance) {
        if (client.player == null) return false;
        float yawError = Math.abs(MathHelper.wrapDegrees(targetYaw - client.player.getYaw()));
        return yawError < tolerance && Math.abs(yawVelocity) < 2.0f;
    }

    /**
     * Остановить сглаживание и обнулить скорости.
     */
    public void stop() {
        active = false;
        tremorYaw = 0f;
        tremorPitch = 0f;
        tremorTargetYaw = 0f;
        tremorTargetPitch = 0f;
        yawVelocity = 0f;
        pitchVelocity = 0f;
    }

    public boolean isActive() {
        return active;
    }

    public float getTargetYaw()   { return targetYaw; }
    public float getTargetPitch() { return targetPitch; }

    // ════════════════════════════════════════════════════════════════════
    //  Внутренние методы
    // ════════════════════════════════════════════════════════════════════

    /**
     * Обновляет микро-тремор руки.
     * С вероятностью 10% каждый тик выбирает новую «цель дрейфа»,
     * затем плавно (lerp) движется к ней. Это создаёт мягкое,
     * неравномерное дрожание прицела — как живая рука.
     */
    private void updateTremor() {
        if (random.nextFloat() < 0.08f) {
            tremorTargetYaw   = (random.nextFloat() - 0.5f) * 2.0f * TREMOR_AMPLITUDE;
            tremorTargetPitch = (random.nextFloat() - 0.5f) * 2.0f * TREMOR_AMPLITUDE;
        }
        tremorYaw   += (tremorTargetYaw   - tremorYaw)   * TREMOR_LERP_SPEED;
        tremorPitch += (tremorTargetPitch  - tremorPitch) * TREMOR_LERP_SPEED;
    }

    private float approach(float current, float target, float maxDelta) {
        return current + MathHelper.clamp(target - current, -maxDelta, maxDelta);
    }
}
