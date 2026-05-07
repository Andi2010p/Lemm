package com.example.lemm;

import org.locationtech.jts.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class CadEngine2d {
    private GeometryFactory factory = new GeometryFactory();
    private List<Geometry> geometries = new ArrayList<>();
    private Stack<List<Geometry>> history = new Stack<>();

    public void saveHistory() {
        history.push(new ArrayList<>(geometries));
    }

    public void undo() {
        if (!history.isEmpty()) geometries = history.pop();
    }

    public void addLine(double x1, double y1, double x2, double y2) {
        saveHistory();
        Coordinate[] coords = {new Coordinate(x1, y1), new Coordinate(x2, y2)};
        geometries.add(factory.createLineString(coords));
    }

    public void addRect(double x1, double y1, double x2, double y2) {
        saveHistory();
        double minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        Coordinate[] coords = {
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)
        };
        geometries.add(factory.createPolygon(coords));
    }

    public void addCircle(double cx, double cy, double radius) {
        saveHistory();
        // JTS creates a circle by buffering a point
        Point point = factory.createPoint(new Coordinate(cx, cy));
        geometries.add(point.buffer(radius));
    }

    // Magnetic Snapping logic
    public Coordinate getSnapPoint(double x, double y, double threshold) {
        Coordinate touch = new Coordinate(x, y);
        Coordinate best = null;
        double minDist = threshold;

        for (Geometry g : geometries) {
            for (Coordinate c : g.getCoordinates()) {
                double d = c.distance(touch);
                if (d < minDist) {
                    minDist = d;
                    best = c;
                }
            }
        }
        return best;
    }

    public List<Geometry> getGeometries() { return geometries; }
    public void clear() { saveHistory(); geometries.clear(); }
}