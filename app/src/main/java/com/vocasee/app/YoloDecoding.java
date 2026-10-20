package com.vocasee.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a YOLO output tensor into boxes, without Android types so it runs in JVM tests.
 * Boxes are in model-input pixel space.
 */
final class YoloDecoding {

    static final int BOX_VALUES = 4;

    /** Reads one value of one box from the output tensor, already dequantized. */
    interface Output {
        float value(int valueIndex, int boxIndex);
    }

    static final class Candidate {
        final int classIndex;
        final float confidence;
        final float left;
        final float top;
        final float right;
        final float bottom;

        Candidate(int classIndex, float confidence, float left, float top, float right, float bottom) {
            this.classIndex = classIndex;
            this.confidence = confidence;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private YoloDecoding() {}

    /** Index into a flat output of shape [values][boxes] (channels first) or [boxes][values]. */
    static int flatIndex(boolean channelsFirst, int valueIndex, int boxIndex,
                         int boxCount, int valuesPerBox) {
        return channelsFirst
                ? valueIndex * boxCount + boxIndex
                : boxIndex * valuesPerBox + valueIndex;
    }

    /** Normalized coordinates (|v| <= 2) are scaled to pixels; pixel coordinates pass through. */
    static float toInputCoordinate(float value, int size) {
        return Math.abs(value) <= 2f ? value * size : value;
    }

    /**
     * Raw head: per box [cx, cy, w, h, class scores...]. With a target class only that
     * score is used; otherwise the best class. Returns the boxes after NMS.
     */
    static List<Candidate> decodeRaw(Output output, int boxCount, int numClasses, int targetClass,
                                     float minimumConfidence, int inputWidth, int inputHeight,
                                     float iouThreshold) {
        List<Candidate> candidates = new ArrayList<>();
        for (int boxIndex = 0; boxIndex < boxCount; boxIndex++) {
            int classIndex = targetClass;
            float score = 0f;
            if (classIndex >= 0) {
                score = output.value(BOX_VALUES + classIndex, boxIndex);
            } else {
                for (int classOffset = 0; classOffset < numClasses; classOffset++) {
                    float candidate = output.value(BOX_VALUES + classOffset, boxIndex);
                    if (candidate > score) {
                        score = candidate;
                        classIndex = classOffset;
                    }
                }
            }
            if (classIndex < 0 || score < minimumConfidence) continue;

            float cx = toInputCoordinate(output.value(0, boxIndex), inputWidth);
            float cy = toInputCoordinate(output.value(1, boxIndex), inputHeight);
            float width = toInputCoordinate(output.value(2, boxIndex), inputWidth);
            float height = toInputCoordinate(output.value(3, boxIndex), inputHeight);
            if (width <= 1f || height <= 1f) continue;

            candidates.add(new Candidate(classIndex, score,
                    cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f));
        }
        return nms(candidates, iouThreshold);
    }

    /** End-to-end head: per box [left, top, right, bottom, confidence, class]. */
    static List<Candidate> decodeNms(Output output, int boxCount, int numClasses, int targetClass,
                                     float minimumConfidence, int inputWidth, int inputHeight,
                                     float iouThreshold) {
        List<Candidate> candidates = new ArrayList<>();
        for (int boxIndex = 0; boxIndex < boxCount; boxIndex++) {
            float confidence = output.value(4, boxIndex);
            int classIndex = Math.round(output.value(5, boxIndex));
            if (confidence < minimumConfidence || classIndex < 0 || classIndex >= numClasses
                    || (targetClass >= 0 && classIndex != targetClass)) {
                continue;
            }

            float left = toInputCoordinate(output.value(0, boxIndex), inputWidth);
            float top = toInputCoordinate(output.value(1, boxIndex), inputHeight);
            float right = toInputCoordinate(output.value(2, boxIndex), inputWidth);
            float bottom = toInputCoordinate(output.value(3, boxIndex), inputHeight);
            if (right <= left || bottom <= top) continue;

            candidates.add(new Candidate(classIndex, confidence, left, top, right, bottom));
        }
        return nms(candidates, iouThreshold);
    }

    /** Greedy per-class NMS, strongest first. Sorts the given list in place. */
    static List<Candidate> nms(List<Candidate> candidates, float iouThreshold) {
        candidates.sort((first, second) -> Float.compare(second.confidence, first.confidence));
        List<Candidate> result = new ArrayList<>();
        boolean[] removed = new boolean[candidates.size()];
        for (int first = 0; first < candidates.size(); first++) {
            if (removed[first]) continue;
            Candidate strongest = candidates.get(first);
            result.add(strongest);
            for (int second = first + 1; second < candidates.size(); second++) {
                if (!removed[second]
                        && candidates.get(second).classIndex == strongest.classIndex
                        && iou(strongest, candidates.get(second)) > iouThreshold) {
                    removed[second] = true;
                }
            }
        }
        return result;
    }

    static float iou(Candidate first, Candidate second) {
        float left = Math.max(first.left, second.left);
        float top = Math.max(first.top, second.top);
        float right = Math.min(first.right, second.right);
        float bottom = Math.min(first.bottom, second.bottom);
        float intersection = Math.max(0f, right - left) * Math.max(0f, bottom - top);
        float firstArea = Math.max(0f, first.right - first.left) * Math.max(0f, first.bottom - first.top);
        float secondArea = Math.max(0f, second.right - second.left) * Math.max(0f, second.bottom - second.top);
        return intersection / (firstArea + secondArea - intersection + 1e-6f);
    }
}
