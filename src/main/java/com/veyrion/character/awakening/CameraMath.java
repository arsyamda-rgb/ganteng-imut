package com.veyrion.character.awakening;

import java.util.List;

/**
 * Pure maths of the awakening cinematic: where the camera is and where it looks as a function of time. No Bukkit, so
 * it is checked without a server (see dev-checks/CameraMathCheck.java).
 *
 * <p>A camera {@link Pose} is cylindrical around the altar centre: a radius, an angle (degrees, measured from the
 * side of the altar where the player stands; 0 = the player's side, 90 = a quarter turn counter-clockwise seen from
 * above), a height and the height of the point it looks at (all relative to the ritual centre). A {@link Shot}
 * moves from one pose to another; shots follow each other, each lasting its share ({@code weight}) of the whole.
 */
final class CameraMath {

    enum Ease { LINEAR, SMOOTH, IN, OUT }

    record Pose(double radius, double angle, double height, double look) {}

    record Shot(double weight, Ease ease, Pose from, Pose to) {}

    private CameraMath() {
    }

    static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1.0);
    }

    static double ease(Ease ease, double u) {
        double t = clamp01(u);
        return switch (ease) {
            case LINEAR -> t;
            case SMOOTH -> t * t * (3.0 - 2.0 * t);
            case IN -> t * t;
            case OUT -> 1.0 - (1.0 - t) * (1.0 - t);
        };
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    static Pose lerp(Pose a, Pose b, double t) {
        return new Pose(lerp(a.radius, b.radius, t), lerp(a.angle, b.angle, t), lerp(a.height, b.height, t),
                lerp(a.look, b.look, t));
    }

    /** Total weight of the shots (at least a tiny positive number, so dividing by it is always safe). */
    static double totalWeight(List<Shot> shots) {
        double total = 0;
        for (Shot shot : shots) {
            total += Math.max(0.0, shot.weight());
        }
        return Math.max(total, 1e-9);
    }

    /**
     * The pose at {@code t} (0..1) of the whole cinematic. Before 0 it is the first pose, after 1 the last.
     *
     * @throws IllegalArgumentException if there is no shot
     */
    static Pose poseAt(List<Shot> shots, double t) {
        if (shots.isEmpty()) {
            throw new IllegalArgumentException("no shots");
        }
        double target = clamp01(t) * totalWeight(shots);
        double start = 0;
        for (int i = 0; i < shots.size(); i++) {
            Shot shot = shots.get(i);
            double weight = Math.max(0.0, shot.weight());
            boolean last = i == shots.size() - 1;
            if (target < start + weight || last) {
                double u = weight <= 0 ? 1.0 : (target - start) / weight;
                return lerp(shot.from(), shot.to(), ease(shot.ease(), u));
            }
            start += weight;
        }
        return shots.get(shots.size() - 1).to();
    }

    /** Index of the shot playing at {@code t} (0..1). */
    static int shotIndexAt(List<Shot> shots, double t) {
        double target = clamp01(t) * totalWeight(shots);
        double start = 0;
        for (int i = 0; i < shots.size(); i++) {
            start += Math.max(0.0, shots.get(i).weight());
            if (target < start) {
                return i;
            }
        }
        return shots.size() - 1;
    }

    /**
     * World position of a pose.
     *
     * @param baseAngle bearing (radians, atan2(dz, dx)) of the direction that counts as angle 0
     * @return {x, y, z}
     */
    static double[] position(double cx, double cy, double cz, double baseAngle, Pose pose) {
        double a = baseAngle + Math.toRadians(pose.angle());
        return new double[] {cx + Math.cos(a) * pose.radius(), cy + pose.height(), cz + Math.sin(a) * pose.radius()};
    }

    /**
     * Minecraft yaw and pitch (degrees) for looking from (fx, fy, fz) at (tx, ty, tz).
     * Yaw 0 looks towards +Z, 90 towards -X; pitch is positive when looking down.
     *
     * @return {yaw, pitch}
     */
    static float[] look(double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx;
        double dy = ty - fy;
        double dz = tz - fz;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double yaw = flat < 1e-9 && Math.abs(dy) < 1e-9 ? 0.0 : Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, flat));
        return new float[] {(float) yaw, (float) pitch};
    }
}
