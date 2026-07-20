package me.cortex.voxy.client.voxymap;

/** A named 3D map marker, tied to a dimension. */
public final class Waypoint {
    public String name;
    public int x;
    public int y;
    public int z;
    public int color;
    public String dimension;

    public Waypoint(String name, int x, int y, int z, int color, String dimension) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.dimension = dimension;
    }
}
