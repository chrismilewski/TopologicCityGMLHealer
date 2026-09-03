package de.mpsc.lod2tolod3.util;

/** Einfache 3D-Punkt-Klasse. */
public class Point3D {

    /** Epsilon fuer Punkt-Gleichheit (XYZ-Vergleich). */
    private static final double POINT_EQUALITY_EPS = 1e-6;

    public final double x;
    public final double y;
    public final double z;

    public Point3D(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean nearlyEquals(Point3D other) {
        if (other == null) return false;
        return Math.abs(x - other.x) < POINT_EQUALITY_EPS
                && Math.abs(y - other.y) < POINT_EQUALITY_EPS
                && Math.abs(z - other.z) < POINT_EQUALITY_EPS;
    }

    @Override
    public String toString() {
        return String.format("(%.3f, %.3f, %.3f)", x, y, z);
    }
}
