package com.example.lemm;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A small curated library of GOLD-STANDARD worked solutions used as few-shot examples ("in-context
 * training"). For each problem we inject the ONE most relevant exemplar into the solver prompt, so the
 * model sees exactly the quality, structure and reasoning we want — private analysis, the
 * ===SOLUTION=== marker, correct drawing commands, and tiny, coordinate-free steps — and copies it.
 *
 * Exemplars are written in English (they teach FORMAT + method); the model still writes the real
 * answer in the student's language. Keywords are matched in EN/RU/HY so the right template is chosen
 * even for translated problems; anything unmatched falls back to the right-triangle exemplar.
 */
public final class SolutionExemplars {

    private SolutionExemplars() {}

    private static final class Ex {
        final String[] keys;
        final String text;
        Ex(String[] keys, String text) { this.keys = keys; this.text = text; }
    }

    /** Picks the best-matching exemplar for a problem (falls back to the right-triangle one). */
    public static String pickFor(String problem) {
        if (problem == null || problem.trim().isEmpty()) return RIGHT_TRIANGLE;
        String p = problem.toLowerCase(Locale.ROOT);
        int best = 0;
        String chosen = RIGHT_TRIANGLE;
        for (Ex ex : ALL) {
            int score = 0;
            for (String k : ex.keys) if (p.contains(k)) score++;
            if (score > best) { best = score; chosen = ex.text; }
        }
        return chosen;
    }

    // ---- Circle / tangent ----
    private static final String CIRCLE_TANGENT =
            "EXAMPLE PROBLEM: From a point P outside a circle with centre O and radius 6, a tangent touches " +
            "the circle at T. OP = 10. Find the length PT.\n" +
            "(Private working — hidden from the student:)\n" +
            "A tangent is perpendicular to the radius at the point where it touches. So OT ⟂ PT and triangle " +
            "OTP has a right angle at T. OT = 6 (a radius), OP = 10 (the far side = hypotenuse), PT = the other " +
            "leg. Pythagoras: PT = √(OP² − OT²) = √(100 − 36) = √64 = 8. Sanity check: 6-8-10 is a right " +
            "triangle. ✓ Place T on the circle and make PT the tangent (perpendicular to OT) so they really meet.\n" +
            "===SOLUTION===\n" +
            "CIRCLE3D:c,0,0,0,60\n" +
            "DRAW3D:O,0,0,0\n" +
            "DRAW3D:T,0,60,0\n" +
            "DRAW3D:P,140,60,0\n" +
            "LINE3D:O,T\n" +
            "LINE3D:P,T\n" +
            "LINE3D:O,P\n" +
            "ANGLE3D:T,O,P,90\n" +
            "GIVEN:\nA circle with centre O and radius 6. P is a point outside it, and the line from P just " +
            "touches the circle at T (a tangent). The distance OP = 10. We want the length of PT.\n" +
            "STEP 1: Use the tangent–radius fact\nA tangent touches a circle at one point and is square (90°) to " +
            "the radius there. So the angle at T, between OT and PT, is a right angle.\n" +
            "STEP 2: Name the right triangle\nTriangle OTP now has a right angle at T, with OT = 6 and OP = 10, " +
            "and OP is the longest side (the hypotenuse).\n" +
            "STEP 3: Use the Pythagorean theorem\nPT² = OP² − OT² = 10² − 6² = 100 − 36 = 64.\n" +
            "STEP 4: Take the square root\nPT = √64 = 8.\n" +
            "FINAL ANSWER: PT = 8\n";

    // ---- Solid volume (cone/cylinder/sphere/…) ----
    private static final String SOLID_VOLUME =
            "EXAMPLE PROBLEM: Find the volume of a cone whose base radius is 3 cm and height is 4 cm. Give the " +
            "answer in terms of π.\n" +
            "(Private working — hidden from the student:)\n" +
            "Cone volume = ⅓·π·r²·h. r = 3, h = 4. V = ⅓·π·9·4 = 12π cm³ ≈ 37.7 cm³. Units are cm³ (a volume). " +
            "Reasonable size. ✓ A cone is a real 3D body, so draw it with CONE3D (Y is up).\n" +
            "===SOLUTION===\n" +
            "CONE3D:cone,0,0,0,60,150,1.0\n" +
            "GIVEN:\nA cone standing on its circular base. The base radius r = 3 cm and the height h = 4 cm. We " +
            "want its volume.\n" +
            "STEP 1: Write the volume formula\nFor a cone, Volume = ⅓ × π × r² × h.\n" +
            "STEP 2: Put in the numbers\nV = ⅓ × π × 3² × 4 = ⅓ × π × 9 × 4.\n" +
            "STEP 3: Simplify\nV = ⅓ × 36 × π = 12π cm³.\n" +
            "FINAL ANSWER: V = 12π cm³ ≈ 37.7 cm³\n";

    // ---- Area / perimeter ----
    private static final String AREA =
            "EXAMPLE PROBLEM: A triangle has a base of 8 cm and a height of 5 cm. Find its area.\n" +
            "(Private working — hidden from the student:)\n" +
            "Triangle area = ½·base·height = ½·8·5 = 20 cm². Units cm² (an area). ✓ Draw a triangle and its " +
            "height as a construction segment from the apex down to the base.\n" +
            "===SOLUTION===\n" +
            "DRAW3D:A,0,0,0\n" +
            "DRAW3D:B,160,0,0\n" +
            "DRAW3D:C,60,100,0\n" +
            "FOOT:H,C,A,B\n" +
            "LINE3D:A,B\n" +
            "LINE3D:B,C\n" +
            "LINE3D:C,A\n" +
            "LINE3D:C,H,red\n" +
            "ANGLE3D:H,C,B,90\n" +
            "GIVEN:\nA triangle ABC. Its base AB = 8 cm and the height from C down to the base (the red segment " +
            "CH) = 5 cm. We want the area.\n" +
            "STEP 1: Write the area formula\nArea of a triangle = ½ × base × height.\n" +
            "STEP 2: Put in the numbers\nArea = ½ × 8 × 5.\n" +
            "STEP 3: Compute\nArea = ½ × 40 = 20 cm².\n" +
            "FINAL ANSWER: Area = 20 cm²\n";

    // ---- Right triangle / Pythagoras (also the default) ----
    private static final String RIGHT_TRIANGLE =
            "EXAMPLE PROBLEM: In right triangle ABC the right angle is at C. The legs are AC = 3 and BC = 4. " +
            "Find the hypotenuse AB.\n" +
            "(Private working — hidden from the student:)\n" +
            "Right angle at C ⇒ AC and BC are the two legs, AB is the hypotenuse (opposite the right angle). " +
            "Pythagoras: AB² = 3² + 4² = 9 + 16 = 25 ⇒ AB = 5. Check: 3-4-5 is a classic right triangle. ✓\n" +
            "===SOLUTION===\n" +
            "DRAW3D:C,0,0,0\n" +
            "DRAW3D:A,0,90,0\n" +
            "DRAW3D:B,120,0,0\n" +
            "LINE3D:C,A\n" +
            "LINE3D:C,B\n" +
            "LINE3D:A,B\n" +
            "ANGLE3D:C,A,B,90\n" +
            "GIVEN:\nA right triangle ABC with the right angle at corner C. The two shorter sides (legs) are " +
            "AC = 3 and BC = 4. We want AB, the longest side opposite the right angle (the hypotenuse).\n" +
            "STEP 1: Choose the right rule\nThe triangle has a right angle, so we use the Pythagorean theorem: " +
            "(one leg)² + (other leg)² = (hypotenuse)².\n" +
            "STEP 2: Put in the numbers\nAB² = AC² + BC² = 3² + 4² = 9 + 16 = 25.\n" +
            "STEP 3: Undo the square\nAB = √25 = 5.\n" +
            "FINAL ANSWER: AB = 5\n";

    private static final List<Ex> ALL = Arrays.asList(
            new Ex(new String[]{
                    "tangent", "touches the circle", "touch the circle",
                    "касат", "шошап"}, CIRCLE_TANGENT),
            new Ex(new String[]{
                    "volume", "cone", "cylinder", "sphere", "pyramid", "prism", "cuboid", "cube",
                    "объ", "объём", "объем", "конус", "цилиндр", "шар", "сфера", "пирамид", "призм", "куб",
                    "ծավալ", "կոն", "գլան", "գունդ", "բուրգ", "պրիզմ", "խորանարդ"}, SOLID_VOLUME),
            new Ex(new String[]{
                    "area", "perimeter",
                    "площад", "периметр",
                    "մակերես", "պարագիծ", "մակերևույթ"}, AREA),
            new Ex(new String[]{
                    "right triangle", "pythag", "hypotenuse", "leg",
                    "прямоуголь", "гипотенуз", "катет", "пифагор",
                    "ուղղանկյուն", "ներքնաձիգ", "էջ", "պյութագոր"}, RIGHT_TRIANGLE)
    );
}
