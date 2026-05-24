package com.example.lemm;

import org.locationtech.jts.geom.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public class CadEngine2d {

    public static class AngleAnnotation {
        public LineString line1, line2;
        public double angleValue;
        public AngleAnnotation(LineString l1, LineString l2, double val) {
            this.line1 = l1; this.line2 = l2; this.angleValue = val;
        }
    }

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
    private List<Coordinate> allSnapPoints = new ArrayList<>();
    private Stack<CadState> history = new Stack<>();
    private List<AngleAnnotation> angleAnnotations = new ArrayList<>();
    private Map<Geometry, Double> circleRadiusAngles = new HashMap<>();

    private int labelCounter = 0;

    public void saveHistory() { history.push(new CadState(geometries, namedPoints, labelCounter)); }

    public void undo() {
        if (!history.isEmpty()) {
            CadState state = history.pop();
            geometries = new ArrayList<>(state.geos);
            namedPoints = new ArrayList<>(state.pts);
            labelCounter = state.counter;
        }
        rebuildPoints();
    }
    public void addVisualAngle(Geometry l1, Geometry l2, double angleDegrees) {
        if (l1 instanceof LineString && l2 instanceof LineString) {
            angleAnnotations.add(new AngleAnnotation((LineString)l1, (LineString)l2, angleDegrees));
        }
    }
    public void rebuildPoints() {
        allSnapPoints.clear();
        for (Geometry g : geometries) {
            // 1. Add Vertices
            for (Coordinate c : g.getCoordinates()) addUniqueSnapPoint(c);
            // 2. Add Center
            addUniqueSnapPoint(g.getCentroid().getCoordinate());
            // 3. Add Midpoints
            Coordinate[] coords = g.getCoordinates();
            for (int i = 0; i < coords.length - 1; i++) {
                addUniqueSnapPoint(new Coordinate((coords[i].x + coords[i+1].x)/2, (coords[i].y + coords[i+1].y)/2));
            }
        }
    }

    private void addUniqueSnapPoint(Coordinate newC) {
        for (Coordinate existing : allSnapPoints) if (existing.distance(newC) < 0.1) return;
        allSnapPoints.add(newC);
    }

    public Coordinate getSnapPoint(double x, double y, double threshold) {
        Coordinate touch = new Coordinate(x, y);
        Coordinate best = null; double minD = threshold;
        for (Coordinate snap : allSnapPoints) {
            double d = snap.distance(touch);
            if (d < minD) { minD = d; best = snap; }
        }
        return best;
    }

    // --- CRITICAL FIX: Angle Math Corrected ---
    public void setAngleBetweenLines(LineString refLine, LineString moveLine, double targetDegrees) {
        saveHistory();
        Coordinate pivot = findSharedVertex(refLine, moveLine);
        if (pivot == null) pivot = moveLine.getCoordinates()[0]; // Fallback

        // Find the ends of the lines that are NOT the pivot
        Coordinate refEnd = refLine.getCoordinates()[0].distance(pivot) < 0.1 ? refLine.getCoordinates()[1] : refLine.getCoordinates()[0];
        Coordinate moveEnd = moveLine.getCoordinates()[0].distance(pivot) < 0.1 ? moveLine.getCoordinates()[1] : moveLine.getCoordinates()[0];

        double length = pivot.distance(moveEnd);

        // Calculate the absolute angle of the reference line radiating OUTWARD from the pivot
        double refAngle = Math.atan2(refEnd.y - pivot.y, refEnd.x - pivot.x);

        // Calculate the current angle of the moving line radiating OUTWARD from the pivot
        double currentMoveAngle = Math.atan2(moveEnd.y - pivot.y, moveEnd.x - pivot.x);

        // Figure out if the line is currently clockwise or counter-clockwise relative to the refLine
        double diff = currentMoveAngle - refAngle;

        // Normalize the difference to be between -PI and PI
        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff <= -Math.PI) diff += 2 * Math.PI;

        // If diff is positive, it's drawn on one side, if negative, the other side.
        double direction = diff >= 0 ? 1.0 : -1.0;

        // Apply the new angle on the SAME side it was already drawn on
        double newAngle = refAngle + (direction * Math.toRadians(targetDegrees));

        // Calculate the new X and Y for the end of the moving line
        Coordinate newEnd = new Coordinate(pivot.x + length * Math.cos(newAngle), pivot.y + length * Math.sin(newAngle));
        updateNamedPointCoords(moveEnd, newEnd);

        // Update the geometry in the engine
        Geometry newLine = factory.createLineString(new Coordinate[]{pivot, newEnd});
        int index = geometries.indexOf(moveLine);
        if (index != -1) {
            geometries.set(index, newLine);

            // Update the Angle Annotations on the screen
            angleAnnotations.removeIf(a -> a.line1 == refLine || a.line1 == moveLine || a.line2 == refLine || a.line2 == moveLine);
            angleAnnotations.add(new AngleAnnotation(refLine, (LineString) newLine, targetDegrees));
        }
        rebuildPoints();
    }

    public Geometry addLine(double x1, double y1, double x2, double y2) {
        saveHistory();
        Coordinate[] coords = {new Coordinate(x1, y1), new Coordinate(x2, y2)};
        Geometry g = factory.createLineString(coords);
        geometries.add(g);
        assignLabels(coords);
        rebuildPoints();
        return g;
    }

    public Geometry addRect(double x1, double y1, double x2, double y2) {
        saveHistory();
        double minX = Math.min(x1, x2), maxX = Math.max(x1, x2), minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        Coordinate[] coords = {new Coordinate(minX, minY), new Coordinate(maxX, minY), new Coordinate(maxX, maxY), new Coordinate(minX, maxY), new Coordinate(minX, minY)};
        Geometry g = factory.createPolygon(coords);
        geometries.add(g);
        assignLabels(coords);
        rebuildPoints();
        return g;
    }

    public Geometry addCircle(double cx, double cy, double radius) {
        saveHistory();
        Coordinate center = new Coordinate(cx, cy);
        Geometry g = factory.createPoint(center).buffer(radius);
        geometries.add(g);
        circleRadiusAngles.put(g, Math.toRadians(45));
        assignLabels(new Coordinate[]{center});
        rebuildPoints();
        return g;
    }

    public Geometry addPolygon(List<Coordinate> points) {
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

    public Geometry resizeLine(Geometry oldLine, double newLength) {
        int index = geometries.indexOf(oldLine);
        if (index == -1) return oldLine;
        saveHistory();
        Coordinate[] c = oldLine.getCoordinates();
        double angle = Math.atan2(c[1].y - c[0].y, c[1].x - c[0].x);
        Coordinate newEnd = new Coordinate(c[0].x + newLength * Math.cos(angle), c[0].y + newLength * Math.sin(angle));
        updateNamedPointCoords(c[1], newEnd);
        Geometry newLine = factory.createLineString(new Coordinate[]{c[0], newEnd});
        geometries.set(index, newLine);
        for(AngleAnnotation a : angleAnnotations) { if(a.line1 == oldLine) a.line1 = (LineString)newLine; if(a.line2 == oldLine) a.line2 = (LineString)newLine; }
        rebuildPoints();
        return newLine;
    }

    public Geometry resizeCircle(Geometry oldCirc, double newRadius) {
        int index = geometries.indexOf(oldCirc);
        if (index == -1) return oldCirc;
        saveHistory();
        double angle = getCircleRadiusAngle(oldCirc);
        Geometry newCirc = factory.createPoint(oldCirc.getCentroid().getCoordinate()).buffer(newRadius);
        geometries.set(index, newCirc);
        circleRadiusAngles.put(newCirc, angle);
        rebuildPoints();
        return newCirc;
    }

    public Geometry resizeRect(Geometry oldRect, double newW, double newH) {
        int index = geometries.indexOf(oldRect);
        if (index == -1) return oldRect;
        saveHistory();
        Coordinate origin = oldRect.getCoordinates()[0];
        Coordinate[] newC = {origin, new Coordinate(origin.x + newW, origin.y), new Coordinate(origin.x + newW, origin.y + newH), new Coordinate(origin.x, origin.y + newH), origin};
        for(int i=0; i<4; i++) updateNamedPointCoords(oldRect.getCoordinates()[i], newC[i]);
        Geometry newRect = factory.createPolygon(newC);
        geometries.set(index, newRect);
        rebuildPoints();
        return newRect;
    }

    public void deleteGeometry(Geometry g) {
        saveHistory();
        geometries.remove(g);
        circleRadiusAngles.remove(g);
        angleAnnotations.removeIf(a -> a.line1 == g || a.line2 == g);

        List<NamedPoint> keep = new ArrayList<>();
        for (Geometry geo : geometries) {
            for (Coordinate c : geo.getCoordinates()) {
                for (NamedPoint np : namedPoints) {
                    if (Math.hypot(np.x - c.x, np.y - c.y) < 0.1 && !keep.contains(np)) keep.add(np);
                }
            }
        }
        namedPoints.clear(); namedPoints.addAll(keep);
        rebuildPoints();
    }
    // New method: Only adds a label if we explicitly ask for it
    public void addExplicitLabel(double x, double y, String textLabel) {
        namedPoints.add(new NamedPoint(x, y, textLabel));
    }
    private void assignLabels(Coordinate[] coords) {
        // to datark lini
    }

    private String getNextLabel() {
        int index = labelCounter++;
        return String.valueOf((char)('A' + (index % 26))) + (index >= 26 ? (index / 26) : "");
    }

    private void updateNamedPointCoords(Coordinate oldC, Coordinate newC) {
        for (NamedPoint np : namedPoints) if (Math.hypot(np.x - oldC.x, np.y - oldC.y) < 0.1) { np.x = newC.x; np.y = newC.y; }
    }

    public double getCircleRadiusAngle(Geometry circle) { return circleRadiusAngles.getOrDefault(circle, Math.toRadians(45)); }

    public Coordinate findSharedVertex(LineString l1, LineString l2) {
        for (Coordinate p1 : l1.getCoordinates()) for (Coordinate p2 : l2.getCoordinates()) if (p1.distance(p2) < 0.1) return p1;
        return null;
    }

    public List<Geometry> getGeometries() { return geometries; }
    public List<NamedPoint> getNamedPoints() { return namedPoints; }
    public List<Coordinate> getAllSnapPoints() { return allSnapPoints; }
    public List<AngleAnnotation> getAngleAnnotations() { return angleAnnotations; }

    public String getPropertiesText(android.content.Context context, Geometry g) {
        if (g instanceof LineString) {
            return context.getString(R.string.prop_length, g.getLength());
        } else {
            return context.getString(R.string.prop_area, g.getArea());
        }
    }

    public Geometry getGeometryAt(double x, double y, double tol) {
        Point touchPoint = factory.createPoint(new Coordinate(x, y));
        for (int i = geometries.size() - 1; i >= 0; i--) {
            Geometry geo = geometries.get(i);
            if (geo.distance(touchPoint) < tol) {
                return geo;
            }
        }
        return null;
    }

    public void calculateAndSetDrivenDimension(Geometry geo, String type) {
        if (geo == null) return;

        String label = "";
        if (type.equals("LINE")) {
            double len = geo.getLength();
            label = String.format("L: %.1f", len);
        } else if (type.equals("CIRCLE")) {
            double radius = geo.getEnvelopeInternal().getWidth() / 2.0;
            label = String.format("R: %.1f", radius);
        } else if (type.equals("RECT")) {
            double w = geo.getEnvelopeInternal().getWidth();
            double h = geo.getEnvelopeInternal().getHeight();
            label = String.format("%.1f x %.1f", w, h);
        }

        geo.setUserData(label);
    }

    public NamedPoint getNamedPointAt(double x, double y, double tol) {
        for (NamedPoint np : namedPoints) if (Math.hypot(np.x-x, np.y-y) < tol) return np;
        return null;
    }

    public void setGeometriesAndPoints(List<Geometry> g, List<NamedPoint> p) { geometries.clear(); geometries.addAll(g); namedPoints.clear(); namedPoints.addAll(p); rebuildPoints(); }
    public void clear() { geometries.clear(); namedPoints.clear(); angleAnnotations.clear(); circleRadiusAngles.clear(); labelCounter = 0; allSnapPoints.clear(); }
}