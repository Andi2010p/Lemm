package com.example.lemm;

import org.locationtech.jts.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class CadEngine2d {
    private GeometryFactory factory = new GeometryFactory();
    private List<Geometry> geometries = new ArrayList<>();
    private Stack<List<Geometry>> history = new Stack<>();
    private List<Coordinate> centerPoints = new ArrayList<>();

    public void saveHistory() {
        history.push(new ArrayList<>(geometries));
    }

    public void undo() {
        if (!history.isEmpty()) geometries = history.pop();
        rebuildCenterPoints();
    }

    public Geometry addLine(double x1, double y1, double x2, double y2) {
        saveHistory();
        Coordinate[] coords = {new Coordinate(x1, y1), new Coordinate(x2, y2)};
        Geometry g = factory.createLineString(coords);
        geometries.add(g);
        return g;
    }

    public Geometry addRect(double x1, double y1, double x2, double y2) {
        saveHistory();
        double minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2), maxY = Math.max(y1, y2);

        Coordinate[] coords = {
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)
        };
        Geometry g = factory.createPolygon(coords);
        geometries.add(g);
        rebuildCenterPoints();
        return g;
    }

    public Geometry addCircle(double cx, double cy, double radius) {
        if (radius <= 0.001) return null;
        saveHistory();
        Geometry g = factory.createPoint(new Coordinate(cx, cy)).buffer(radius);
        geometries.add(g);
        rebuildCenterPoints();
        return g;
    }

    public Geometry addPolygon(List<Coordinate> points) {
        if (points.size() < 3) return null;
        saveHistory();

        Coordinate[] coords = new Coordinate[points.size() + 1];
        for (int i = 0; i < points.size(); i++) coords[i] = points.get(i);
        coords[points.size()] = points.get(0);

        Geometry g = factory.createPolygon(coords);
        geometries.add(g);
        rebuildCenterPoints();
        return g;
    }

    // --- PARAMETRIC CAD SIZING ---
    public void resizeLine(Geometry oldLine, double newLength) {
        Coordinate[] c = oldLine.getCoordinates();
        double angle = Math.atan2(c[1].y - c[0].y, c[1].x - c[0].x);
        double nx = c[0].x + newLength * Math.cos(angle);
        double ny = c[0].y + newLength * Math.sin(angle);

        Geometry newLine = factory.createLineString(new Coordinate[]{c[0], new Coordinate(nx, ny)});
        newLine.setUserData(String.format("L: %.1f", newLength));
        geometries.set(geometries.indexOf(oldLine), newLine);
    }

    public void resizeCircle(Geometry oldCirc, double newRadius) {
        Coordinate center = oldCirc.getCentroid().getCoordinate();
        Geometry newCirc = factory.createPoint(center).buffer(newRadius);
        newCirc.setUserData(String.format("R: %.1f", newRadius));
        geometries.set(geometries.indexOf(oldCirc), newCirc);
        rebuildCenterPoints();
    }

    public void resizeRect(Geometry oldRect, double newW, double newH) {
        Envelope env = oldRect.getEnvelopeInternal();
        double minX = env.getMinX();
        double minY = env.getMinY();
        Coordinate[] coords = {
                new Coordinate(minX, minY), new Coordinate(minX + newW, minY),
                new Coordinate(minX + newW, minY + newH), new Coordinate(minX, minY + newH),
                new Coordinate(minX, minY)
        };
        Geometry newRect = factory.createPolygon(coords);
        newRect.setUserData(String.format("%.1f x %.1f", newW, newH));
        geometries.set(geometries.indexOf(oldRect), newRect);
        rebuildCenterPoints();
    }

    public void calculateAndSetDrivenDimension(Geometry geo, String type) {
        if (type.equals("LINE")) {
            Coordinate[] c = geo.getCoordinates();
            double len = c[0].distance(c[1]);
            geo.setUserData(String.format("L: %.1f", len));
        } else if (type.equals("CIRCLE")) {
            double r = geo.getEnvelopeInternal().getWidth() / 2.0;
            geo.setUserData(String.format("R: %.1f", r));
        } else if (type.equals("RECT")) {
            double w = geo.getEnvelopeInternal().getWidth();
            double h = geo.getEnvelopeInternal().getHeight();
            geo.setUserData(String.format("%.1f x %.1f", w, h));
        }
    }

    // --- UTILITIES ---
    private void rebuildCenterPoints() {
        centerPoints.clear();
        for (Geometry g : geometries) {
            if (g instanceof Polygon) centerPoints.add(g.getCentroid().getCoordinate());
        }
    }

    public Geometry getGeometryAt(double x, double y, double tolerance) {
        Point p = factory.createPoint(new Coordinate(x, y));
        for (int i = geometries.size() - 1; i >= 0; i--) {
            Geometry g = geometries.get(i);
            if (g.distance(p) < tolerance) return g;
        }
        return null;
    }

    public Coordinate getSnapPoint(double x, double y, double threshold) {
        Coordinate touch = new Coordinate(x, y);
        Coordinate best = null;

        for (Geometry g : geometries) {
            for (Coordinate c : g.getCoordinates()) {
                double d = c.distance(touch);
                if (d < threshold) { threshold = d; best = c; }
            }
        }
        for (Coordinate c : centerPoints) {
            double d = c.distance(touch);
            if (d < threshold) { threshold = d; best = c; }
        }
        return best;
    }
    // NEW: Needed to load drawings from History
    public void setGeometries(List<Geometry> newGeometries) {
        this.geometries.clear();
        this.geometries.addAll(newGeometries);
        rebuildCenterPoints();
    }
    public List<Geometry> getGeometries() { return geometries; }
    public void clear() {
        saveHistory();
        geometries.clear();
        centerPoints.clear();
    }
}