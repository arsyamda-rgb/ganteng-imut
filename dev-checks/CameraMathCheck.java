package com.veyrion.character.awakening;

import java.util.List;

public final class CameraMathCheck {
    static int fails = 0;
    static void check(boolean ok, String what) { if (!ok) { fails++; System.out.println("FAIL: " + what); } }
    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    public static void main(String[] args) {
        CameraMath.Pose a = new CameraMath.Pose(8, 0, 3, 1);
        CameraMath.Pose b = new CameraMath.Pose(4, 90, 1, 2);
        CameraMath.Pose c = new CameraMath.Pose(2, 180, 0, 3);
        List<CameraMath.Shot> shots = List.of(
                new CameraMath.Shot(1, CameraMath.Ease.LINEAR, a, b),
                new CameraMath.Shot(3, CameraMath.Ease.LINEAR, b, c));

        // endpoints and clamping
        check(near(CameraMath.poseAt(shots, 0).radius(), 8), "start radius");
        check(near(CameraMath.poseAt(shots, -5).radius(), 8), "before start clamps");
        check(near(CameraMath.poseAt(shots, 1).radius(), 2), "end radius");
        check(near(CameraMath.poseAt(shots, 9).angle(), 180), "after end clamps");
        // shot boundary at weight 1 of 4 => t = 0.25 : pose b, continuous
        check(near(CameraMath.poseAt(shots, 0.25).radius(), 4), "boundary pose");
        check(Math.abs(CameraMath.poseAt(shots, 0.2499999).radius() - 4.0) < 1e-5, "continuous before boundary");
        // midpoint of shot 1
        check(near(CameraMath.poseAt(shots, 0.125).angle(), 45), "mid shot 1");
        check(CameraMath.shotIndexAt(shots, 0.1) == 0, "index 0");
        check(CameraMath.shotIndexAt(shots, 0.3) == 1, "index 1");
        check(CameraMath.shotIndexAt(shots, 1.0) == 1, "index at end");
        // zero-weight shot never divides by zero
        List<CameraMath.Shot> zero = List.of(new CameraMath.Shot(0, CameraMath.Ease.SMOOTH, a, b));
        check(Double.isFinite(CameraMath.poseAt(zero, 0.5).radius()), "zero weight finite");
        try { CameraMath.poseAt(List.of(), 0.5); check(false, "empty must throw"); } catch (IllegalArgumentException e) { }

        // easing
        for (CameraMath.Ease e : CameraMath.Ease.values()) {
            check(near(CameraMath.ease(e, 0), 0) && near(CameraMath.ease(e, 1), 1), e + " endpoints");
            double prev = -1;
            for (int i = 0; i <= 100; i++) { double v = CameraMath.ease(e, i / 100.0); check(v >= prev - 1e-12, e + " monotonic"); prev = v; }
        }
        check(CameraMath.ease(CameraMath.Ease.IN, 0.5) < 0.5 && CameraMath.ease(CameraMath.Ease.OUT, 0.5) > 0.5, "in slow / out fast");

        // position: base angle 0 = +X; angle 90 = +Z
        double[] p0 = CameraMath.position(10, 64, 20, 0, new CameraMath.Pose(5, 0, 2, 0));
        check(near(p0[0], 15) && near(p0[1], 66) && near(p0[2], 20), "angle 0 -> +X");
        double[] p90 = CameraMath.position(10, 64, 20, 0, new CameraMath.Pose(5, 90, 0, 0));
        check(near(p90[0], 10) && near(p90[2], 25), "angle 90 -> +Z");
        double[] pb = CameraMath.position(0, 0, 0, Math.PI, new CameraMath.Pose(3, 0, 0, 0));
        check(near(pb[0], -3) && near(pb[2], 0), "base angle PI -> -X");
        // radius is constant on a pure orbit
        for (int d = 0; d < 360; d += 15) {
            double[] q = CameraMath.position(0, 0, 0, 0.7, new CameraMath.Pose(6, d, 0, 0));
            check(near(Math.hypot(q[0], q[2]), 6), "orbit radius " + d);
        }

        // look: Minecraft convention
        float[] south = CameraMath.look(0, 0, 0, 0, 0, 5);   // target at +Z -> yaw 0
        check(near(south[0], 0) && near(south[1], 0), "look +Z");
        float[] west = CameraMath.look(0, 0, 0, -5, 0, 0);   // target at -X -> yaw 90
        check(near(west[0], 90), "look -X yaw 90");
        float[] north = CameraMath.look(0, 0, 0, 0, 0, -5);  // yaw 180
        check(near(Math.abs(north[0]), 180), "look -Z yaw 180");
        float[] east = CameraMath.look(0, 0, 0, 5, 0, 0);    // yaw -90
        check(near(east[0], -90), "look +X yaw -90");
        float[] down = CameraMath.look(0, 10, 0, 0, 0, 5);   // looking down => positive pitch
        check(down[1] > 0, "down pitch positive");
        float[] up = CameraMath.look(0, 0, 0, 0, 5, 5);
        check(near(up[1], -45), "up 45");
        float[] same = CameraMath.look(1, 1, 1, 1, 1, 1);
        check(Float.isFinite(same[0]) && Float.isFinite(same[1]), "degenerate look finite");
        float[] straightDown = CameraMath.look(0, 5, 0, 0, 0, 0);
        check(near(straightDown[1], 90) && Float.isFinite(straightDown[0]), "straight down");

        // subtitle timeline
        check(SubtitleTimeline.visibility(-1, 10, 20, 10) == 0, "before");
        check(SubtitleTimeline.visibility(40, 10, 20, 10) == 0, "after");
        check(SubtitleTimeline.visibility(15, 10, 20, 10) == 1.0, "stay");
        double prev = 0;
        for (int t = 0; t < 10; t++) { double v = SubtitleTimeline.visibility(t, 10, 20, 10); check(v > prev && v < 1, "fade in rises " + t); prev = v; }
        prev = 2;
        for (int t = 30; t < 40; t++) { double v = SubtitleTimeline.visibility(t, 10, 20, 10); check(v < prev && v > 0, "fade out falls " + t); prev = v; }
        check(SubtitleTimeline.total(10, 20, 10) == 40, "total");
        check(SubtitleTimeline.visibility(0, 0, 5, 0) == 1.0, "no fade in");
        check(SubtitleTimeline.textOpacity(0) == 0, "opacity 0");
        check(SubtitleTimeline.textOpacity(0.001) == SubtitleTimeline.MIN_VISIBLE, "min visible >= 4");
        check(SubtitleTimeline.textOpacity(1) == 255, "opacity full");
        check(SubtitleTimeline.backgroundAlpha(1, 166) == 166 && SubtitleTimeline.backgroundAlpha(0, 166) == 0, "bg alpha");
        int a65 = SubtitleTimeline.alphaForPercent(65);
        check(a65 >= 153 && a65 <= 179, "60-70% alpha range: " + a65);
        check(SubtitleTimeline.alphaForPercent(60) == 153 && SubtitleTimeline.alphaForPercent(70) == 179, "60/70 values");
        check(SubtitleTimeline.alphaForPercent(500) == 255 && SubtitleTimeline.alphaForPercent(-3) == 0, "percent clamp");

        // snapshot validation
        check(new CameraSnapshot("SURVIVAL", "world", 1, 2, 3, 0, 0, false, false).valid(), "snapshot valid");
        check(!new CameraSnapshot("FLYING", "world", 1, 2, 3, 0, 0, false, false).valid(), "bad mode");
        check(!new CameraSnapshot("SURVIVAL", "", 1, 2, 3, 0, 0, false, false).valid(), "no world");
        check(!new CameraSnapshot("SURVIVAL", "w", Double.NaN, 2, 3, 0, 0, false, false).valid(), "nan");

        System.out.println(fails == 0 ? "ALL CHECKS PASSED" : fails + " CHECK(S) FAILED");
        if (fails != 0) System.exit(1);
    }
}
