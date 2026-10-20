package com.vocasee.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class YoloDecodingTest {

    private static final float EPS = 0.01f;
    private static final float IOU = 0.45f;

    /** values[valueIndex][boxIndex], like a channels-first output. */
    private static YoloDecoding.Output output(float[][] values) {
        return (valueIndex, boxIndex) -> values[valueIndex][boxIndex];
    }

    @Test
    public void flatIndexSupportsBothLayouts() {
        // 3 boxes, 6 values per box.
        assertEquals(2 * 3 + 1, YoloDecoding.flatIndex(true, 2, 1, 3, 6));
        assertEquals(1 * 6 + 2, YoloDecoding.flatIndex(false, 2, 1, 3, 6));
    }

    @Test
    public void normalizedCoordinatesScaleToPixels() {
        assertEquals(320f, YoloDecoding.toInputCoordinate(0.5f, 640), EPS);
        assertEquals(320f, YoloDecoding.toInputCoordinate(320f, 640), EPS);
    }

    // Raw head, 2 classes: [cx, cy, w, h, score0, score1] per box.
    private static final float[][] RAW = {
            { 0.5f, 0.52f, 0.5f, 0.1f },   // cx (normalized)
            { 0.5f, 0.5f, 0.5f, 0.5f },    // cy
            { 0.2f, 0.2f, 0.2f, 0.2f },    // w
            { 0.2f, 0.2f, 0.2f, 0.001f },  // h; box 3 is under one pixel tall
            { 0.9f, 0.8f, 0.1f, 0.9f },    // class 0
            { 0.1f, 0.1f, 0.7f, 0.1f },    // class 1
    };

    @Test
    public void rawHeadDecodesBestClassAndSuppressesOverlaps() {
        List<YoloDecoding.Candidate> boxes = YoloDecoding.decodeRaw(
                output(RAW), 4, 2, -1, 0.3f, 640, 640, IOU);
        // Box 1 overlaps box 0 with the same class and is dropped; box 2 overlaps too but
        // is class 1, so it stays. Box 3 is degenerate.
        assertEquals(2, boxes.size());
        assertEquals(0, boxes.get(0).classIndex);
        assertEquals(0.9f, boxes.get(0).confidence, EPS);
        assertEquals(256f, boxes.get(0).left, EPS);
        assertEquals(384f, boxes.get(0).right, EPS);
        assertEquals(1, boxes.get(1).classIndex);
    }

    @Test
    public void rawHeadUsesOnlyTheTargetClassScore() {
        List<YoloDecoding.Candidate> boxes = YoloDecoding.decodeRaw(
                output(RAW), 4, 2, 1, 0.3f, 640, 640, IOU);
        assertEquals(1, boxes.size());
        assertEquals(1, boxes.get(0).classIndex);
        assertEquals(0.7f, boxes.get(0).confidence, EPS);
    }

    @Test
    public void rawHeadAppliesTheConfidenceThreshold() {
        assertEquals(0, YoloDecoding.decodeRaw(output(RAW), 4, 2, 1, 0.75f, 640, 640, IOU).size());
    }

    // End-to-end head: [left, top, right, bottom, confidence, class] per box.
    private static final float[][] NMS = {
            { 10f, 10f, 300f, 50f },
            { 10f, 10f, 300f, 50f },
            { 100f, 100f, 400f, 40f },     // box 3 has right < left
            { 100f, 100f, 400f, 80f },
            { 0.9f, 0.6f, 0.8f, 0.9f },
            { 0f, 30f, 2f, 0f },           // box 1 has an unknown class
    };

    @Test
    public void nmsHeadRejectsInvalidClassesAndBoxes() {
        List<YoloDecoding.Candidate> boxes = YoloDecoding.decodeNms(
                output(NMS), 4, 24, -1, 0.3f, 640, 640, IOU);
        assertEquals(2, boxes.size());
        assertEquals(0.9f, boxes.get(0).confidence, EPS);
        assertEquals(0, boxes.get(0).classIndex);
        assertEquals(2, boxes.get(1).classIndex);
    }

    @Test
    public void nmsHeadFiltersToTheTargetClass() {
        List<YoloDecoding.Candidate> boxes = YoloDecoding.decodeNms(
                output(NMS), 4, 24, 2, 0.3f, 640, 640, IOU);
        assertEquals(1, boxes.size());
        assertEquals(300f, boxes.get(0).left, EPS);
    }

    @Test
    public void nmsKeepsSeparateObjectsOfTheSameClass() {
        List<YoloDecoding.Candidate> boxes = YoloDecoding.nms(new ArrayList<>(Arrays.asList(
                new YoloDecoding.Candidate(0, 0.5f, 0, 0, 10, 10),
                new YoloDecoding.Candidate(0, 0.9f, 100, 100, 110, 110))), IOU);
        assertEquals(2, boxes.size());
        assertEquals(0.9f, boxes.get(0).confidence, EPS);
    }

    @Test
    public void iouOfIdenticalAndDisjointBoxes() {
        YoloDecoding.Candidate box = new YoloDecoding.Candidate(0, 1f, 0, 0, 10, 10);
        assertEquals(1f, YoloDecoding.iou(box, box), EPS);
        assertEquals(0f, YoloDecoding.iou(box, new YoloDecoding.Candidate(0, 1f, 20, 20, 30, 30)), EPS);
        // Half overlap: 50 / (100 + 100 - 50).
        assertEquals(1f / 3f, YoloDecoding.iou(box, new YoloDecoding.Candidate(0, 1f, 5, 0, 15, 10)), EPS);
    }
}
