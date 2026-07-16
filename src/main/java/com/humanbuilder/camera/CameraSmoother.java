package com.humanbuilder.camera;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Плавное управление камерой (Yaw/Pitch) игрока.
 *
 * Использует Critically Damped Spring (критически демпфированная пружина)
 * вместо обычного Lerp. Это даёт:
 *   — плавное ускорение в начале поворота
 *   — лёгкий overshoot (перелёт) мимо цели
 *   — естественное затухание, как у руки с мышкой
 *
 * Дополнительно накладывается микро-тремор руки (±0.15°) для полной
 * имитации человека.
 */
public class CameraSmoother {

    private final MinecraftClient client;
    private final Random random = new Random();

    // ── Текущее состояние ──────────────────────────────────────────────
    private float targetYaw;
    private float targetPitch;

    // ── Тремор руки ────────────────────────────────────────────────────
    private float tremorYaw = 0f;
    private float tremorPitch = 0f;
    private float tremorTargetYaw = 0f;
    private float tremorTargetPitch = 0f;
    private static final float TREMOR_AMPLITUDE = 0.15f;   // макс отклонение, градусы
    private static final float TREMOR_LERP_SPEED = 0.12f;  // скорость дрейфа тремора

    // ── Управление ─────────────────────────────────────────────────────
    private boolean active = false;
    private static final float CONVERGENCE_THRESHOLD = 1.0f;  // считаем «навёлся», градусы

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
        targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
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
    /**
     * Мгновенно поворачивает камеру к цели без сглаживания.
     * Используется во время бега (WALKING), чтобы избежать бега по спирали.
     */
    public void snapToTarget() {
        if (client.player == null) return;
        float finalYaw = MathHelper.wrapDegrees(targetYaw);
        float finalPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);
        client.player.setYaw(finalYaw);
        client.player.setPitch(finalPitch);
    }

    /**
     * Вызывается каждый клиентский тик из TickHandler.
     * Двигает yaw/pitch игрока на один шаг пружины + тремор.
     */
    public void tick() {
        if (!active || client.player == null) return;

        // Нормализуем текущие углы игрока к диапазону [-180, 180]
        float currentYaw = MathHelper.wrapDegrees(client.player.getYaw());
        float currentPitch = MathHelper.wrapDegrees(client.player.getPitch());

        // ── Тремор руки ────────────────────────────────────────────────
        updateTremor();

        // Нормализуем целевые углы
        float targetYawNorm = MathHelper.wrapDegrees(targetYaw + tremorYaw);
        float targetPitchNorm = MathHelper.clamp(targetPitch + tremorPitch, -90.0f, 90.0f);

        // ── Расстояние до цели ─────────────────────────────────────────
        float yawError = MathHelper.wrapDegrees(targetYawNorm - currentYaw);
        float pitchError = targetPitchNorm - currentPitch;


        // ── Линейная интерполяция (LERP) с ограничением скорости ───────
        // Коэффициент LERP = 0.65f (65% расстояния преодолеваем за один тик)
        float lerpFactor = 0.65f;

        float deltaYaw = yawError * lerpFactor;
        float deltaPitch = pitchError * lerpFactor;

        // Ограничиваем максимальную скорость поворота за один тик (1 тик = 50 мс)
        // 35 градусов за тик = 700 градусов в секунду (быстрое наведение)
        float maxSpeedYaw = 35.0f;
        float maxSpeedPitch = 25.0f;

        deltaYaw = MathHelper.clamp(deltaYaw, -maxSpeedYaw, maxSpeedYaw);
        deltaPitch = MathHelper.clamp(deltaPitch, -maxSpeedPitch, maxSpeedPitch);

        // Расчёт новых углов
        float newYaw = MathHelper.wrapDegrees(currentYaw + deltaYaw);
        float newPitch = MathHelper.clamp(currentPitch + deltaPitch, -90.0f, 90.0f);

        // ── Применяем ──────────────────────────────────────────────────
        client.player.setYaw(newYaw);
        client.player.setPitch(newPitch);
    }

    /**
     * Прицел достаточно близок к цели?
     * Используется BuildExecutor для перехода LOOKING → PLACING.
     */
    public boolean isConverged() {
        if (client.player == null) return false;
        float yawErr  = Math.abs(MathHelper.wrapDegrees(targetYaw - client.player.getYaw()));
        float pitchErr = Math.abs(targetPitch - client.player.getPitch());
        return yawErr < CONVERGENCE_THRESHOLD && pitchErr < CONVERGENCE_THRESHOLD;
    }

    /**
     * Остановить сглаживание и обнулить скорости.
     */
    public void stop() {
        active = false;
        tremorYaw = 0f;
        tremorPitch = 0f;
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
     * С вероятностью 15% каждый тик выбирает новую «цель дрейфа»,
     * затем плавно (lerp) движется к ней. Это создаёт мягкое,
     * неравномерное дрожание прицела — как живая рука.
     */
    private void updateTremor() {
        if (random.nextFloat() < 0.15f) {
            tremorTargetYaw   = (random.nextFloat() - 0.5f) * 2.0f * TREMOR_AMPLITUDE;
            tremorTargetPitch = (random.nextFloat() - 0.5f) * 2.0f * TREMOR_AMPLITUDE;
        }
        tremorYaw   += (tremorTargetYaw   - tremorYaw)   * TREMOR_LERP_SPEED;
        tremorPitch += (tremorTargetPitch  - tremorPitch) * TREMOR_LERP_SPEED;
    }
}
