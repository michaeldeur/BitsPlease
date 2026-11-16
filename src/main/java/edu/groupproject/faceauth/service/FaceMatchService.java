package edu.groupproject.faceauth.service;

import java.util.List;

/**
 * Compares face "descriptors" — 128 numbers the AI model produces for a face.
 * Two photos of the same person give descriptors that are close together;
 * different people are far apart. We measure Euclidean distance.
 *
 *   distance < ~0.5  → very likely the same person
 *   distance > ~0.6  → very likely a different person
 */
public class FaceMatchService {

    public static final int DESCRIPTOR_LENGTH = 128;

    /** Rejects anything that isn't exactly 128 finite numbers in a sane range. */
    public static double[] validate(List<Double> values) {
        if (values == null || values.size() != DESCRIPTOR_LENGTH) {
            throw new IllegalArgumentException("Face descriptor must contain exactly " + DESCRIPTOR_LENGTH + " numbers");
        }
        double[] out = new double[DESCRIPTOR_LENGTH];
        for (int i = 0; i < DESCRIPTOR_LENGTH; i++) {
            Double v = values.get(i);
            if (v == null || !Double.isFinite(v) || Math.abs(v) > 1.0) {
                throw new IllegalArgumentException("Face descriptor contains an invalid value at index " + i);
            }
            out[i] = v;
        }
        return out;
    }

    public static double euclideanDistance(double[] a, double[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("Length mismatch");
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    /** Smallest distance between the live sample and any enrolled sample. */
    public static double bestDistance(double[] live, List<double[]> enrolled) {
        double best = Double.MAX_VALUE;
        for (double[] e : enrolled) {
            best = Math.min(best, euclideanDistance(live, e));
        }
        return best;
    }
}
