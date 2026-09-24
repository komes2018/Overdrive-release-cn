package com.overdrive.app.parking.signage;

import java.util.ArrayList;
import java.util.List;

/**
 * 8-connected component labelling over a binary mask, iterative (explicit
 * queue) so a large text region can never overflow the stack. Used to turn a
 * DBNet-style text probability map into candidate text boxes without pulling
 * in OpenCV.
 */
public final class ConnectedComponents {

    /** Axis-aligned component with its pixel count. */
    public static final class Component {
        public int minX, minY, maxX, maxY;
        public int area;
        /** Sum of mask scores inside the component (for a mean-score filter). */
        public double scoreSum;

        public int width() { return maxX - minX + 1; }
        public int height() { return maxY - minY + 1; }
        public float meanScore() { return area == 0 ? 0f : (float) (scoreSum / area); }
    }

    private ConnectedComponents() {}

    /**
     * @param score   row-major {@code w*h} probability map (0..1)
     * @param w       map width
     * @param h       map height
     * @param thresh  binarisation threshold
     * @param minArea drop components smaller than this many pixels
     */
    public static List<Component> label(float[] score, int w, int h, float thresh, int minArea) {
        List<Component> out = new ArrayList<>();
        if (score == null || w <= 0 || h <= 0 || score.length < w * h) return out;
        boolean[] visited = new boolean[w * h];
        int[] queue = new int[w * h];
        for (int start = 0; start < w * h; start++) {
            if (visited[start] || score[start] < thresh) continue;
            Component c = new Component();
            c.minX = c.maxX = start % w;
            c.minY = c.maxY = start / w;
            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int idx = queue[head++];
                int x = idx % w, y = idx / w;
                c.area++;
                c.scoreSum += score[idx];
                if (x < c.minX) c.minX = x;
                if (x > c.maxX) c.maxX = x;
                if (y < c.minY) c.minY = y;
                if (y > c.maxY) c.maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int n = ny * w + nx;
                        if (visited[n] || score[n] < thresh) continue;
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
            }
            if (c.area >= minArea) out.add(c);
        }
        return out;
    }

    /**
     * DBNet "unclip": grow a shrunk text box back to the glyph extent. Uses
     * the polygon-offset formula on the axis-aligned box:
     * {@code d = area * ratio / perimeter}.
     */
    public static int[] unclip(Component c, float ratio, int maxW, int maxH) {
        float area = (float) c.width() * c.height();
        float perimeter = 2f * (c.width() + c.height());
        int d = perimeter <= 0 ? 0 : Math.round(area * ratio / perimeter);
        int x0 = Math.max(0, c.minX - d);
        int y0 = Math.max(0, c.minY - d);
        int x1 = Math.min(maxW - 1, c.maxX + d);
        int y1 = Math.min(maxH - 1, c.maxY + d);
        return new int[] {x0, y0, x1, y1};
    }
}
