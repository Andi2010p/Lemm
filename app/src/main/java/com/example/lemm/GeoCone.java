// D:/codes/Homeworks.Uwc/Lemm/app/src/main/java/com/example/lemm/GeoCone.java
package com.example.lemm;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;

public class GeoCone {
    public String label;
    public float centerX, centerY, centerZ; // Base center
    public float radius;
    public float height;
    public int color = Color.BLUE; // Default color for cones

    // Apex point will be (centerX, centerY, centerZ + height) if base is on XY plane

    public GeoCone(String label, float centerX, float centerY, float centerZ, float radius, float height) {
        this.label = label;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = radius;
        this.height = height;
    }
    public GeoCone deepCopy() {
        return new GeoCone(label, centerX, centerY, centerZ, radius, height);
    }

}