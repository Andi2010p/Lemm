package com.example.lemm;

import org.locationtech.jts.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class CadEngine2d {

    public static class NamedPoint {
        public double x, y;
        public String label;
        public NamedPoint(double x, double y, String label) { this.x = x; this.y = y; this.label = label; }
    }

    private class CadState {
        List<Geometry> geos;
        List<NamedPoint> pts;
        int counter;
        CadState(List<Geometry> g, List<NamedPoint> p, int c) {
            geos = new ArrayList<>(g);
            pts = new ArrayList<>();
            for(NamedPoint np : p) pts.add(new NamedPoint(np.x, np.y, np.label));
            counter = c;
        }
    }

    private GeometryFactory factory = new GeometryFactory();
    private List<Geometry> geometries = new ArrayList<>();
    private List<NamedPoint> namedPoints = new ArrayList<>();
    private Stack<CadState> history = new Stack<>();

    private List<Coordinate> centerPoints = new ArrayList<>();
    private List<Coordinate> midPoints = new ArrayList<>();
    private int labelCounter = 0;

    public void saveHistory() {
        history.push(new CadState(geometries, namedPoints, labelCounter));
    }

    public void undo() {
        if (!history.isEmpty()) {
            CadState state = history.pop();
            geometries = new ArrayList<>(state.geos);
            namedPoints = new ArrayList<>(state.pts);
            labelCounter = state.counter;
        }
        rebuildPoints();
    }

    private String getNextLabel() {
        int index = labelCounter++;
        if (index < 26) return String.valueOf((char)('A' + index));
        return String.valueOf((char)('A' + (index % 26))) + (index / 26);
    }

    private void assignLabels(Coordinate[] coords) {
        for (Coordinate c : coords) {
            boolean found = false;
            for (NamedPoint np : namedPoints) {
                if (Math.hypot(np.x - c.x, np.y - c.y) < 0.01) { found = true; break; }
            }
            if (!found) namedPoints.add(new NamedPoint(c.x, c.y, getNextLabel()));
        }
    }

    private void updateNamedPointCoords(Coordinate oldC, Coordinate newC) {
        for (NamedPoint np : namedPoints) {
            if (Math.hypot(np.x - oldC.x, np.y - oldC.y) < 0.01) {
                np.x = newC.x;
                np.y = newC.y;
            }
        }
    }

    public Geometry addLine(double x1, double y1, double x2, double y2) {
        if (Math.hypot(x2 - x1, y2 - y1) < 0.1) return null;
        saveHistory();
        Coordinate[] coords = {new Coordinate(x1, y1), new Coordinate(x2, y2)};
        Geometry g = factory.createLineString(coords);
        geometries.add(g);
        assignLabels(coords);
        rebuildPoints();
        return g;
    }

    public Geometry addRect(double x1, double y1, double x2, double y2) {
        if (Math.abs(x2 - x1) < 0.1 || Math.abs(y2 - y1) < 0.1) return null;
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
        assignLabels(coords);
        rebuildPoints();
        return g;
    }

    public Geometry addCircle(double cx, double cy, double radius) {
        if (radius <= 0.1) return null;
        saveHistory();
        Geometry g = factory.createPoint(new Coordinate(cx, cy)).buffer(radius);
        geometries.add(g);
        assignLabels(new Coordinate[]{new Coordinate(cx, cy)}); // Label Center only
        rebuildPoints();
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
        assignLabels(coords);
        rebuildPoints();
        return g;
    }

    public void resizeLine(Geometry oldLine, double newLength) {
        if (newLength <= 0.1) return;
        Coordinate[] c = oldLine.getCoordinates();
        double angle = Math.atan2(c[1].y - c[0].y, c[1].x - c[0].x);
        Coordinate newEnd = new Coordinate(c[0].x + newLength * Math.cos(angle), c[0].y + newLength * Math.sin(angle));

        updateNamedPointCoords(c[1], newEnd);

        Geometry newLine = factory.createLineString(new Coordinate[]{c[0], newEnd});
        newLine.setUserData(String.format("L: %.1f", newLength));
        geometries.set(geometries.indexOf(oldLine), newLine);
        rebuildPoints();
    }

    public void resizeCircle(Geometry oldCirc, double newRadius) {
        if (newRadius <= 0.1) return;
        Coordinate center = oldCirc.getCentroid().getCoordinate();
        Geometry newCirc = factory.createPoint(center).buffer(newRadius);
        newCirc.setUserData(String.format("R: %.1f", newRadius));
        geometries.set(geometries.indexOf(oldCirc), newCirc);
        rebuildPoints();
    }

    public void resizeRect(Geometry oldRect, double newW, double newH) {
        if (newW <= 0.1 || newH <= 0.1) return;
        Envelope env = oldRect.getEnvelopeInternal();
        double minX = env.getMinX(); double minY = env.getMinY();
        Coordinate[] oldC = oldRect.getCoordinates();

        Coordinate[] newC = {
                new Coordinate(minX, minY), new Coordinate(minX + newW, minY),
                new Coordinate(minX + newW, minY + newH), new Coordinate(minX, minY + newH),
                new Coordinate(minX, minY)
        };

        for (int i=0; i<4; i++) updateNamedPointCoords(oldC[i], newC[i]);

        Geometry newRect = factory.createPolygon(newC);
        newRect.setUserData(String.format("%.1f x %.1f", newW, newH));
        geometries.set(geometries.indexOf(oldRect), newRect);
        rebuildPoints();
    }

    public void calculateAndSetDrivenDimension(Geometry geo, String type) {
        if (type.equals("LINE")) {
            Coordinate[] c = geo.getCoordinates();
            geo.setUserData(String.format("L: %.1f", c[0].distance(c[1])));
        } else if (type.equals("CIRCLE")) {
            geo.setUserData(String.format("R: %.1f", geo.getEnvelopeInternal().getWidth() / 2.0));
        } else if (type.equals("RECT")) {
            geo.setUserData(String.format("%.1f x %.1f", geo.getEnvelopeInternal().getWidth(), geo.getEnvelopeInternal().getHeight()));
        }
    }

    private void rebuildPoints() {
        centerPoints.clear(); midPoints.clear();
        for (Geometry g : geometries) {
            if (g instanceof Polygon) centerPoints.add(g.getCentroid().getCoordinate());
            Coordinate[] coords = g.getCoordinates();
            for (int i = 0; i < coords.length - 1; i++) {
                midPoints.add(new Coordinate((coords[i].x + coords[i+1].x) / 2.0, (coords[i].y + coords[i+1].y) / 2.0));
            }
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

    public NamedPoint getNamedPointAt(double x, double y, double tol) {
        NamedPoint best = null;
        double min = tol;
        for (NamedPoint np : namedPoints) {
            double d = Math.hypot(np.x - x, np.y - y);
            if (d < min) { min = d; best = np; }
        }
        return best;
    }

    public Coordinate getSnapPoint(double x, double y, double threshold) {
        Coordinate touch = new Coordinate(x, y); Coordinate best = null; double minD = threshold;
        for (Geometry g : geometries) for (Coordinate c : g.getCoordinates()) { double d = c.distance(touch); if (d < minD) { minD = d; best = c; } }
        for (Coordinate c : centerPoints) { double d = c.distance(touch); if (d < minD) { minD = d; best = c; } }
        for (Coordinate c : midPoints) { double d = c.distance(touch); if (d < minD) { minD = d; best = c; } }
        return best;
    }

    public String getPropertiesText(Geometry g) {
        if (g == null) return "";
        StringBuilder sb = new StringBuilder();
        if (g instanceof Polygon) {
            sb.append("Type: Polygon/Circle\n");
            sb.append(String.format("Area: %.2f\nPerimeter: %.2f", g.getArea(), g.getLength()));
        } else if (g instanceof LineString) {
            sb.append("Type: Line\n");
            sb.append(String.format("Length: %.2f", g.getLength()));
        }
        return sb.toString();
    }

    public void setGeometriesAndPoints(List<Geometry> newGeometries, List<NamedPoint> newPoints) {
        this.geometries.clear(); this.geometries.addAll(newGeometries);
        this.namedPoints.clear();

        if (newPoints != null && !newPoints.isEmpty()) {
            this.namedPoints.addAll(newPoints);
            labelCounter = namedPoints.size();
        } else {
            labelCounter = 0;
            for(Geometry g : newGeometries) assignLabels(g.getCoordinates());
        }
        rebuildPoints();
    }

    public List<Geometry> getGeometries() { return geometries; }
    public List<NamedPoint> getNamedPoints() { return namedPoints; }

    public void clear() {
        saveHistory();
        geometries.clear(); namedPoints.clear();
        labelCounter = 0;
        rebuildPoints();
    }
}