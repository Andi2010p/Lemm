package com.example.lemm;

import org.locationtech.jts.geom.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public class CadEngine2d {
    private Stack<CadState> redoStack = new Stack<>(); // Add this line!

    private GeometryFactory factory = new GeometryFactory();
    private List<Geometry> geometries = new ArrayList<>();
    private List<NamedPoint> namedPoints = new ArrayList<>();
    private List<Coordinate> allSnapPoints = new ArrayList<>();
    private Stack<CadState> history = new Stack<>();
    private List<AngleAnnotation> angleAnnotations = new ArrayList<>();
    private Map<Geometry, Double> circleRadiusAngles = new HashMap<>();

    private int labelCounter = 0;
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
        List<AngleAnnotation> anns;
        Map<Geometry, Double> radii;
        int counter;
        CadState(List<Geometry> g, List<NamedPoint> p, List<AngleAnnotation> a, Map<Geometry, Double> r, int c) {
            geos = new ArrayList<>(g);
            pts = new ArrayList<>();
            for(NamedPoint np : p) pts.add(new NamedPoint(np.x, np.y, np.label));
            anns = new ArrayList<>();
            for(AngleAnnotation an : a) anns.add(new AngleAnnotation(an.line1, an.line2, an.angleValue));
            radii = new HashMap<>(r);
            counter = c;
        }
    }


    // Modified: Clearing redo stack on new actions is a critical CAD rule
    public void saveHistory() {
        history.push(new CadState(geometries, namedPoints, angleAnnotations, circleRadiusAngles, labelCounter));
        redoStack.clear();
    }

    // Modified: Saves the undone state to the redo stack
    public void undo() {
        if (!history.isEmpty()) {
            redoStack.push(new CadState(geometries, namedPoints, angleAnnotations, circleRadiusAngles, labelCounter));

            CadState state = history.pop();
            geometries = new ArrayList<>(state.geos);
            namedPoints = new ArrayList<>(state.pts);
            angleAnnotations = new ArrayList<>(state.anns);
            circleRadiusAngles = new HashMap<>(state.radii);
            labelCounter = state.counter;
        }
        rebuildPoints();
    }

    // NEW: Pops a state from the redo stack and loads it
    public void redo() {
        if (!redoStack.isEmpty()) {
            history.push(new CadState(geometries, namedPoints, angleAnnotations, circleRadiusAngles, labelCounter));

            CadState state = redoStack.pop();
            geometries = new ArrayList<>(state.geos);
            namedPoints = new ArrayList<>(state.pts);
            angleAnnotations = new ArrayList<>(state.anns);
            circleRadiusAngles = new HashMap<>(state.radii);
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
            for (Coordinate c : g.getCoordinates()) addUniqueSnapPoint(c);
            addUniqueSnapPoint(g.getCentroid().getCoordinate());
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

    // --- SOLIDWORKS SOLVER: Updates all connected/coincident vertices in real-time ---
    private void updateCoincidentVertices(Coordinate oldC, Coordinate newC) {
        // 1. Update named labels
        for (NamedPoint np : namedPoints) {
            if (Math.hypot(np.x - oldC.x, np.y - oldC.y) < 0.1) {
                np.x = newC.x;
                np.y = newC.y;
            }
        }
        // 2. Update every geometry coordinate sharing this point
        for (int i = 0; i < geometries.size(); i++) {
            Geometry g = geometries.get(i);
            Coordinate[] coords = g.getCoordinates();
            boolean changed = false;
            for (int j = 0; j < coords.length; j++) {
                if (coords[j].distance(oldC) < 0.1) {
                    coords[j].x = newC.x;
                    coords[j].y = newC.y;
                    changed = true;
                }
            }
            if (changed) {
                Geometry updated = null;
                if (g instanceof LineString) {
                    updated = factory.createLineString(coords);
                } else if (g instanceof Polygon) {
                    updated = factory.createPolygon(coords);
                }
                if (updated != null) {
                    updated.setUserData(g.getUserData());
                    geometries.set(i, updated);
                }
            }
        }
    }

    public void setAngleBetweenLines(LineString refLine, LineString moveLine, double targetDegrees) {
        saveHistory();
        Coordinate pivot = findSharedVertex(refLine, moveLine);
        if (pivot == null) pivot = moveLine.getCoordinates()[0];

        Coordinate refEnd = refLine.getCoordinates()[0].distance(pivot) < 0.1 ? refLine.getCoordinates()[1] : refLine.getCoordinates()[0];
        Coordinate moveEnd = moveLine.getCoordinates()[0].distance(pivot) < 0.1 ? moveLine.getCoordinates()[1] : moveLine.getCoordinates()[0];

        double length = pivot.distance(moveEnd);
        double refAngle = Math.atan2(refEnd.y - pivot.y, refEnd.x - pivot.x);
        double currentMoveAngle = Math.atan2(moveEnd.y - pivot.y, moveEnd.x - pivot.x);
        double diff = currentMoveAngle - refAngle;

        while (diff > Math.PI) diff -= 2 * Math.PI;
        while (diff <= -Math.PI) diff += 2 * Math.PI;

        double direction = diff >= 0 ? 1.0 : -1.0;
        double newAngle = refAngle + (direction * Math.toRadians(targetDegrees));

        Coordinate newEnd = new Coordinate(pivot.x + length * Math.cos(newAngle), pivot.y + length * Math.sin(newAngle));

        // Capture the slot before the parametric update: updateCoincidentVertices
        // replaces moveLine with a new instance, so indexOf(moveLine) would be -1 afterwards.
        int index = geometries.indexOf(moveLine);

        // Use parametric coincident updates
        updateCoincidentVertices(moveEnd, newEnd);

        Geometry newLine = factory.createLineString(new Coordinate[]{pivot, newEnd});
        if (index != -1) {
            geometries.set(index, newLine);
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

        // Parametric Vertex update
        updateCoincidentVertices(c[1], newEnd);

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

        // Parametric Multi-vertex update
        for(int i=0; i<4; i++) updateCoincidentVertices(oldRect.getCoordinates()[i], newC[i]);

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

    public void addExplicitLabel(double x, double y, String textLabel) {
        namedPoints.add(new NamedPoint(x, y, textLabel));
    }

    private void assignLabels(Coordinate[] coords) {}

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
        if (g == null) return "";
        java.util.Locale L = java.util.Locale.US;
        Coordinate[] c = g.getCoordinates();

        if (g instanceof LineString) {
            if (c.length == 2) {
                double dx = c[1].x - c[0].x;
                double dy = c[1].y - c[0].y;
                // Angle to the horizontal axis, measured the SAME way the canvas draws its angle arcs
                // (true angle between the line and the x-axis, 0°–180°), so the panel value matches the
                // figure. A near-horizontal line reads ~3°, not ~177°.
                double angle = Math.abs(Math.toDegrees(Math.atan2(dy, dx)));
                double vy = -dy; // y-up for an intuitive slope sign (rising-right = positive)
                String slope = Math.abs(dx) < 1e-6
                        ? "∞"
                        : String.format(L, "%.2f", vy / dx);
                return context.getString(R.string.info_line) + "\n"
                        + context.getString(R.string.info_length) + ": " + String.format(L, "%.1f", g.getLength()) + "\n"
                        + context.getString(R.string.info_endpoints) + ": ("
                        + String.format(L, "%.0f", c[0].x) + ", " + String.format(L, "%.0f", c[0].y) + ") → ("
                        + String.format(L, "%.0f", c[1].x) + ", " + String.format(L, "%.0f", c[1].y) + ")\n"
                        + context.getString(R.string.info_angle) + ": " + String.format(L, "%.1f", angle) + "°\n"
                        + context.getString(R.string.info_slope) + ": " + slope;
            }
            return context.getString(R.string.info_polyline) + "\n"
                    + context.getString(R.string.info_vertices) + ": " + c.length + "\n"
                    + context.getString(R.string.info_length) + ": " + String.format(L, "%.1f", g.getLength());
        }

        // Polygon: detect circle-like shapes (created via buffer, or tagged "R:" by a dimension).
        int sides = Math.max(0, c.length - 1);
        boolean isCircle = sides > 12
                || (g.getUserData() != null && g.getUserData().toString().trim().toUpperCase(L).startsWith("R"));
        if (isCircle) {
            double r = g.getEnvelopeInternal().getWidth() / 2.0;
            return context.getString(R.string.info_circle) + "\n"
                    + context.getString(R.string.info_radius) + ": " + String.format(L, "%.1f", r) + "\n"
                    + context.getString(R.string.info_diameter) + ": " + String.format(L, "%.1f", 2 * r) + "\n"
                    + context.getString(R.string.info_circumference) + ": " + String.format(L, "%.1f", 2 * Math.PI * r) + "\n"
                    + context.getString(R.string.info_area) + ": " + String.format(L, "%.1f", g.getArea());
        }
        return context.getString(R.string.info_polygon) + " (" + sides + ")\n"
                + context.getString(R.string.info_sides) + ": " + sides + "\n"
                + context.getString(R.string.info_perimeter) + ": " + String.format(L, "%.1f", g.getLength()) + "\n"
                + context.getString(R.string.info_area) + ": " + String.format(L, "%.1f", g.getArea());
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
    // Finds which specific segment index of a closed shape is closest to the touch point
    public int getClosestSegmentIndex(Geometry geom, double x, double y, double tol) {
        if (!(geom instanceof Polygon)) return -1;
        Coordinate[] coords = geom.getCoordinates();
        Point touchPoint = factory.createPoint(new Coordinate(x, y));
        double minDist = tol;
        int closestIdx = -1;

        for (int i = 0; i < coords.length - 1; i++) {
            LineString segment = factory.createLineString(new Coordinate[]{coords[i], coords[i+1]});
            double dist = segment.distance(touchPoint);
            if (dist < minDist) {
                minDist = dist;
                closestIdx = i;
            }
        }
        return closestIdx;
    }

    public Geometry deletePolygonSegment(Geometry poly, int segIdx) {
        if (!(poly instanceof Polygon)) return poly;
        saveHistory();
        Coordinate[] coords = poly.getCoordinates();
        List<Coordinate> remaining = new ArrayList<>();

        // Wrap around the loop from (segIdx + 1) to (segIdx) to form an open line string
        for (int j = 0; j < coords.length - 1; j++) {
            int idx = (segIdx + 1 + j) % (coords.length - 1);
            remaining.add(coords[idx]);
        }

        Geometry openLine = factory.createLineString(remaining.toArray(new Coordinate[0]));
        int index = geometries.indexOf(poly);
        if (index != -1) {
            geometries.set(index, openLine);
        }
        rebuildPoints();
        return openLine;
    }
    public NamedPoint getNamedPointAt(double x, double y, double tol) {
        for (NamedPoint np : namedPoints) if (Math.hypot(np.x-x, np.y-y) < tol) return np;
        return null;
    }
    // SOLIDWORKS SOLVER: Resizes a single edge of a polygon and deforms the rest to match
    public Geometry resizePolygonSegment(Geometry poly, int segIdx, double newLength) {
        if (!(poly instanceof Polygon) || segIdx == -1) return poly;
        saveHistory();

        // Capture the slot now: updateCoincidentVertices replaces poly with a new
        // Polygon instance, so indexOf(poly) would be -1 afterwards.
        int index = geometries.indexOf(poly);

        Coordinate[] c = poly.getCoordinates();
        Coordinate p1 = c[segIdx];
        Coordinate p2 = c[segIdx + 1];

        // Calculate the direction angle of this specific edge
        double angle = Math.atan2(p2.y - p1.y, p2.x - p1.x);
        Coordinate newEnd = new Coordinate(p1.x + newLength * Math.cos(angle), p1.y + newLength * Math.sin(angle));

        // Move the end vertex and pull all connected lines along with it!
        updateCoincidentVertices(p2, newEnd);

        rebuildPoints();
        return index != -1 ? geometries.get(index) : poly;
    }
    public void setGeometriesAndPoints(List<Geometry> g, List<NamedPoint> p) { geometries.clear(); geometries.addAll(g); namedPoints.clear(); namedPoints.addAll(p); rebuildPoints(); }
    // Modified: Wipes both stacks
    public void clear() {
        geometries.clear();
        namedPoints.clear();
        angleAnnotations.clear();
        circleRadiusAngles.clear();
        labelCounter = 0;
        allSnapPoints.clear();
        history.clear();
        redoStack.clear(); // Clear the redo stack as well
    }}