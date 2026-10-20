package com.vocasee.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;


public class HandGuidanceOverlay extends View {

    private final Paint handPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Rect objectBox;
    private Rect handBox;
    private String guidanceText = "";

    public HandGuidanceOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        handPaint.setStyle(Paint.Style.STROKE);
        handPaint.setStrokeWidth(5f);
        handPaint.setColor(Color.CYAN);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(4f);
        linePaint.setColor(Color.YELLOW);


        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(34f);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(6f, 0f, 2f, Color.BLACK);
    }

    public void showGuidance(Rect objectBox, Rect handBox, String guidanceText) {
        this.objectBox = new Rect(objectBox);
        this.handBox = new Rect(handBox);
        this.guidanceText = guidanceText == null ? "" : guidanceText;
        setVisibility(VISIBLE);
        invalidate();
    }

    public void clear() {
        objectBox = null;
        handBox = null;
        guidanceText = "";
        setVisibility(GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // PROCESS 8 - VISUAL HAND FEEDBACK: Draw the hand box, object-direction line, and movement instruction.
        super.onDraw(canvas);
        if (objectBox == null || handBox == null) return;

        canvas.drawRect(handBox, handPaint);
        canvas.drawLine(
                handBox.exactCenterX(),
                handBox.exactCenterY(),
                objectBox.exactCenterX(),
                objectBox.exactCenterY(),
                linePaint);

        float labelX = Math.max(8f, handBox.left);
        float labelY = Math.max(42f, handBox.top - 14f);
        canvas.drawText("Hand", labelX, labelY, textPaint);

        if (!guidanceText.isEmpty()) {
            canvas.drawText(guidanceText, 24f, getHeight() - 140f, textPaint);
        }
    }
}
