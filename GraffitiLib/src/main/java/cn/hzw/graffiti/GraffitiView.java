package cn.hzw.graffiti;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.concurrent.CopyOnWriteArrayList;

import cn.forward.androids.utils.LogUtil;

/**
 * Created by Administrator on 2016/9/3.
 */
public class GraffitiView extends View {

    public static final int ERROR_INIT = -1;
    public static final int ERROR_SAVE = -2;

    private static final float VALUE = 1f;

    private final int timeSpan = 80;

    private Bitmap galleryBitmap;
    private HandWrite.GraffitiListener mGraffitiListener;

    private float scale;
    private Bitmap originalBitmap;
    private Canvas myCanvas;

    private float n;
    private int height, width;// 包揽图片的框大小 并非为preview的大�?
    private float centreX, centreY;// 是图片居�?

    private Paint mBitmapPaint;
    private BitmapShader mBitmapShader; // 用于涂鸦的图片上
    private BitmapShader mBitmapShader4C; // 用于view的画布上
    private float transX = 0, transY = 0;

    private int mode;
    private float radius;
    private int color;

    private Path mCurrPath; // 当前手写的路径
    private Path mCanvasPath; //

    private boolean mIsPainting = false; // 是否正在绘制

    private boolean isJustDrawOriginal; // 是否只绘制原图

    private CopyLocation mCopyLocation;

    // 保存涂鸦操作，便于撤销
    private CopyOnWriteArrayList<GraffitiPath> mPathStack = new CopyOnWriteArrayList<GraffitiPath>();
    private CopyOnWriteArrayList<GraffitiPath> pathStackBackup = new CopyOnWriteArrayList<GraffitiPath>();

    /**
     * 画笔
     */
    public enum Pen {
        HAND, // 手绘
        COPY, // 仿制
        ERASER // 橡皮擦
    }

    /**
     * 图形
     */
    public enum Shape {
        HAND_WRITE, //
        ARROW, // 箭头
        LINE, // 直线
        FILL_CIRCLE, // 实心圆
        HOLLOW_CIRCLE, // 空心圆
        FILL_RECT, // 实心矩形
        HOLLOW_RECT, // 空心矩形
    }

    private Pen mPen;
    private Shape mShape;

    private float mTouchDownX, mTouchDownY, mLastTouchX, mLastTouchY, mTouchX, mTouchY;
    private Matrix mShaderMatrix;

    public GraffitiView(Context context, Bitmap bitmap, HandWrite.GraffitiListener listener) {
        super(context);
        galleryBitmap = bitmap;
        mGraffitiListener = listener;
        if (mGraffitiListener == null) {
            throw new RuntimeException("GraffitiListener is null!!!");
        }
        if (galleryBitmap == null) {
            throw new RuntimeException("Bitmap is null!!!");
        }

        init();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setBG();
        mCopyLocation.updateLocation(toX4C(w / 2), toY4C(h / 2));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                mode = 1;
                mTouchDownX = mTouchX = mLastTouchX = event.getX();
                mTouchDownY = mTouchY = mLastTouchY = event.getY();

                mTouchX += VALUE; // 为了仅点击时也能出现绘图，模拟滑动一个像素点
                mTouchY += VALUE;

                if (mPathStack.size() > 3) {// 当前栈大于3，则拷贝到备份栈
                    pathStackBackup.addAll(mPathStack);
                    mPathStack.clear();
                }

                if (mPen == Pen.COPY && mCopyLocation.isInIt(toX4C(mTouchX), toY4C(mTouchY))) { // 点击copy
                    mCopyLocation.isRelocating = true;
                    mCopyLocation.isCopying = false;
                } else {
                    if (mPen == Pen.COPY) {
                        if (!mCopyLocation.isCopying) {
                            mCopyLocation.setStartPosition(toX4C(mTouchX), toY4C(mTouchY));
                            resetMatrix();
                        }
                        mCopyLocation.isCopying = true;
                    }
                    mCopyLocation.isRelocating = false;
                    if (mShape == Shape.HAND_WRITE) { // 手写
                        mCurrPath = new Path();
                        mCurrPath.moveTo(toX(mTouchDownX), toY(mTouchDownY));
                        mCanvasPath.reset();
                        mCanvasPath.moveTo(toX4C(mTouchDownX), toY4C(mTouchDownY));

                        // 为了仅点击时也能出现绘图，必须移动path
                        mCanvasPath.quadTo(
                                toX4C(mLastTouchX),
                                toY4C(mLastTouchY),
                                toX4C((mTouchX + mLastTouchX) / 2),
                                toY4C((mTouchY + mLastTouchY) / 2));
                    } else {  // 画图形

                    }
                    mIsPainting = true;
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                mLastTouchX = mTouchX;
                mLastTouchY = mTouchY;
                mTouchX = event.getX();
                mTouchY = event.getY();

                if (mCopyLocation.isRelocating) { // 正在定位location
                    mCopyLocation.updateLocation(toX4C(mTouchX), toY4C(mTouchY));
                    mCopyLocation.isRelocating = false;
                } else {
                    if (mIsPainting) {

                        if (mPen == Pen.COPY) {
                            mCopyLocation.updateLocation(mCopyLocation.mCopyStartX + toX4C(mTouchX) - mCopyLocation.mTouchStartX,
                                    mCopyLocation.mCopyStartY + toY4C(mTouchY) - mCopyLocation.mTouchStartY);
                        }

                        // 把操作记录到加入的堆栈中
                        if (mShape == Shape.HAND_WRITE) { // 手写
                            mCurrPath.quadTo(
                                    toX(mLastTouchX),
                                    toY(mLastTouchY),
                                    toX((mTouchX + mLastTouchX) / 2),
                                    toY((mTouchY + mLastTouchY) / 2));
                            mPathStack.add(GraffitiPath.toPath(mPen, radius, color, mCurrPath, mPen == Pen.COPY ? new Matrix(mShaderMatrix) : null));
                        } else {  // 画图形
                            mPathStack.add(GraffitiPath.toShape(mPen, mShape, radius, color,
                                    toX(mTouchDownX), toY(mTouchDownY), toX(mTouchX), toY(mTouchY),
                                    mPen == Pen.COPY ? new Matrix(mShaderMatrix) : null));
                        }
                        draw(myCanvas, mPathStack, false); // 保存到图片中
                        mIsPainting = false;
                    }
                }

                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode < 2) { // 单点滑动
                    mLastTouchX = mTouchX;
                    mLastTouchY = mTouchY;
                    mTouchX = event.getX();
                    mTouchY = event.getY();

                    if (mCopyLocation.isRelocating) { // 正在定位location
                        mCopyLocation.updateLocation(toX4C(mTouchX), toY4C(mTouchY));
                    } else {
                        if (mPen == Pen.COPY) {
                            mCopyLocation.updateLocation(mCopyLocation.mCopyStartX + toX4C(mTouchX) - mCopyLocation.mTouchStartX,
                                    mCopyLocation.mCopyStartY + toY4C(mTouchY) - mCopyLocation.mTouchStartY);
                        }
                        if (mShape == Shape.HAND_WRITE) { // 手写
                            mCurrPath.quadTo(
                                    toX(mLastTouchX),
                                    toY(mLastTouchY),
                                    toX((mTouchX + mLastTouchX) / 2),
                                    toY((mTouchY + mLastTouchY) / 2));
                            mCanvasPath.quadTo(
                                    toX4C(mLastTouchX),
                                    toY4C(mLastTouchY),
                                    toX4C((mTouchX + mLastTouchX) / 2),
                                    toY4C((mTouchY + mLastTouchY) / 2));
                        } else { // 画图形

                        }
                    }
                } else { // 多点

                }

                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                mode -= 1;

                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                mode += 1;

                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    public void init() {

        scale = 1f;
        radius = 30;
        color = Color.RED;
        mBitmapPaint = new Paint();
        mBitmapPaint.setStrokeWidth(radius);
        mBitmapPaint.setColor(color);
        mBitmapPaint.setAntiAlias(true);
        mBitmapPaint.setStrokeJoin(Paint.Join.ROUND);
        mBitmapPaint.setStrokeCap(Paint.Cap.ROUND);// 圆滑

        mPen = Pen.COPY;
        mShape = Shape.HAND_WRITE;

        this.mBitmapShader = new BitmapShader(this.galleryBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        this.mBitmapShader4C = new BitmapShader(this.galleryBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);

        mShaderMatrix = new Matrix();
        mCanvasPath = new Path();
        mCopyLocation = new CopyLocation(150, 150);
    }

    private void setBG() {// 不用resize preview
        int w = galleryBitmap.getWidth();
        int h = galleryBitmap.getHeight();
        float nw = w * 1f / getWidth();
        float nh = h * 1f / getHeight();
        if (nw > nh) {
            n = 1 / nw;
            width = getWidth();
            height = (int) (h * n);
        } else {
            n = 1 / nh;
            width = (int) (w * n);
            height = getHeight();
        }
        // 使图片居中
        centreX = (getWidth() - width) / 2f;
        centreY = (getHeight() - height) / 2f;

        initCanvas();
        resetMatrix();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (galleryBitmap.isRecycled() || originalBitmap.isRecycled()) {
            return;
        }

        canvas.scale(n * scale, n * scale);
        if (isJustDrawOriginal) { // 只绘制原图
            canvas.drawBitmap(galleryBitmap, (centreX + transX) / (n * scale), (centreY + transY) / (n * scale), null);
            return;
        }

        // 绘制涂鸦
        canvas.drawBitmap(originalBitmap, (centreX + transX) / (n * scale), (centreY + transY) / (n * scale), null);

        if (mIsPainting) {  //画在view的画布上
            // 画触摸的路径
            mBitmapPaint.setStrokeWidth(radius);
            mBitmapPaint.setColor(color);
            if (mShape == Shape.HAND_WRITE) { // 手写
                draw(canvas, mPen, mBitmapPaint, mCanvasPath, null, true);
            } else {  // 画图形
                draw(canvas, mPen, mShape, mBitmapPaint,
                        toX4C(mTouchDownX), toY4C(mTouchDownY), toX4C(mTouchX), toY4C(mTouchY), null, true);
            }
        }

        if (mPen == Pen.COPY) {
            mCopyLocation.drawItSelf(canvas);
        }

    }

    private void draw(Canvas canvas, Pen pen, Paint paint, Path path, Matrix matrix, boolean is4Canvas) {
        resetPaint(pen, paint, is4Canvas, matrix);

        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(path, paint);

    }

    private void draw(Canvas canvas, Pen pen, Shape shape, Paint paint, float sx, float sy, float dx, float dy, Matrix matrix, boolean is4Canvas) {
        resetPaint(pen, paint, is4Canvas, matrix);

        paint.setStyle(Paint.Style.STROKE);

        switch (shape) { // 绘制图形
            case ARROW:
                paint.setStyle(Paint.Style.FILL);
                DrawUtil.drawArrow(canvas, sx, sy, dx, dy, paint);
                break;
            case LINE:
                DrawUtil.drawLine(canvas, sx, sy, dx, dy, paint);
                break;
            case FILL_CIRCLE:
                paint.setStyle(Paint.Style.FILL);
            case HOLLOW_CIRCLE:
                DrawUtil.drawCircle(canvas, sx, sy,
                        (float) Math.sqrt((sx - dx) * (sx - dx) + (sy - dy) * (sy - dy)), paint);
                break;
            case FILL_RECT:
                paint.setStyle(Paint.Style.FILL);
            case HOLLOW_RECT:
                DrawUtil.drawRect(canvas, sx, sy, dx, dy, paint);
                break;
            default:
                LogUtil.i("hzw", "unknown shape");
        }
    }


    private void draw(Canvas canvas, CopyOnWriteArrayList<GraffitiPath> pathStack, boolean is4Canvas) {
        // 还原堆栈中的记录的操作
        for (GraffitiPath path : pathStack) {
            mBitmapPaint.setStrokeWidth(path.mStrokeWidth);
            mBitmapPaint.setColor(path.mColor);
            if (mShape == Shape.HAND_WRITE) { // 手写
                draw(canvas, path.mPen, mBitmapPaint, path.mPath, path.mMatrix, is4Canvas);
            } else { // 画图形
                draw(canvas, path.mPen, path.mShape, mBitmapPaint,
                        path.mSx, path.mSy, path.mDx, path.mDy, path.mMatrix, is4Canvas);
            }
        }
    }

    private void resetPaint(Pen pen, Paint paint, boolean is4Canvas, Matrix matrix) {
        switch (pen) { // 设置画笔
            case HAND:
                paint.setShader(null);
                break;
            case COPY:
                if (is4Canvas) { // 画在view的画布上
                    paint.setShader(this.mBitmapShader4C);
                } else { // 调整copy图片位置
                    mBitmapShader.setLocalMatrix(matrix);
                    paint.setShader(this.mBitmapShader);
                }
                break;
            case ERASER:
                if (is4Canvas) {
                    paint.setShader(this.mBitmapShader4C);
                } else {
                    mBitmapShader.setLocalMatrix(null);
                    paint.setShader(this.mBitmapShader);
                }
                break;
        }
    }


    /**
     * 将屏幕触摸坐标x转换成在图片中的坐标
     */
    private float toX(float x) {
        return (x - centreX - transX) / (n * scale);
    }

    /**
     * 将屏幕触摸坐标y转换成在图片中的坐标
     */
    private float toY(float y) {
        return (y - centreY - transY) / (n * scale);
    }

    /**
     * 将屏幕触摸坐标x转换成在canvas中的坐标
     */
    private float toX4C(float x) {
        return (x) / (n * scale);
    }

    /**
     * 将屏幕触摸坐标y转换成在canvas中的坐标
     */
    private float toY4C(float y) {
        return (y) / (n * scale);
    }

    private static class GraffitiPath {
        Pen mPen;
        Shape mShape;
        float mStrokeWidth;
        int mColor;
        Path mPath;
        float mSx, mSy, mDx, mDy;
        Matrix mMatrix;

        static GraffitiPath toShape(Pen pen, Shape shape, float width, int color,
                                    float sx, float sy, float dx, float dy, Matrix matrix) {
            GraffitiPath path = new GraffitiPath();
            path.mPen = pen;
            path.mShape = shape;
            path.mStrokeWidth = width;
            path.mColor = color;
            path.mSx = sx;
            path.mSy = sy;
            path.mDx = dx;
            path.mDy = dy;
            path.mMatrix = matrix;
            return path;
        }

        static GraffitiPath toPath(Pen pen, float width, int color, Path p, Matrix matrix) {
            GraffitiPath path = new GraffitiPath();
            path.mPen = pen;
            path.mStrokeWidth = width;
            path.mColor = color;
            path.mPath = p;
            path.mMatrix = matrix;
            return path;
        }
    }

    private void initCanvas() {
        if (originalBitmap != null) {
            originalBitmap.recycle();
        }
        originalBitmap = galleryBitmap.copy(Bitmap.Config.RGB_565, true);
        myCanvas = new Canvas(originalBitmap);
    }

    private void resetMatrix() {
        if (mPen == Pen.COPY) { // 仿制，加上mCopyLocation记录的偏移

            this.mShaderMatrix.set(null);
            this.mShaderMatrix.postTranslate((centreX + transX) / (n * scale) + mCopyLocation.mTouchStartX - mCopyLocation.mCopyStartX,
                    (centreY + transY) / (n * scale) + mCopyLocation.mTouchStartY - mCopyLocation.mCopyStartY);
            this.mBitmapShader4C.setLocalMatrix(this.mShaderMatrix);

            this.mShaderMatrix.set(null);
            this.mShaderMatrix.postTranslate(mCopyLocation.mTouchStartX - mCopyLocation.mCopyStartX, mCopyLocation.mTouchStartY - mCopyLocation.mCopyStartY);
            this.mBitmapShader.setLocalMatrix(this.mShaderMatrix);

        } else {
            this.mShaderMatrix.set(null);
            this.mShaderMatrix.postTranslate((centreX + transX) / (n * scale), (centreY + transY) / (n * scale));
            this.mBitmapShader4C.setLocalMatrix(this.mShaderMatrix);

            this.mShaderMatrix.set(null);
            this.mBitmapShader.setLocalMatrix(this.mShaderMatrix);
        }


    }

    /**
     * 调整图片位置
     */
    private void judgePosition() {
        boolean changed = false;
        if (scale > 1) { // 当图片放大时，图片偏移的位置不能超过屏幕边缘
            if (transX > 0) {
                transX = 0;
                changed = true;
            } else if (transX + width * scale < width) {
                transX = width - width * scale;
                changed = true;
            }
            if (transY > 0) {
                transY = 0;
                changed = true;
            } else if (transY + height * scale < height) {
                transY = height - height * scale;
                changed = true;
            }
        } else { // 当图片缩小时，图片只能在屏幕可见范围内移动
            if (transX + galleryBitmap.getWidth() * n * scale > width) { // scale<1是preview.width不用乘scale
                transX = width - galleryBitmap.getWidth() * n * scale;
                changed = true;
            } else if (transX < 0) {
                transX = 0;
                changed = true;
            }
            if (transY + galleryBitmap.getHeight() * n * scale > height) {
                transY = height - galleryBitmap.getHeight() * n * scale;
                changed = true;
            } else if (transY < 0) {
                transY = 0;
                changed = true;
            }
        }
        if (changed) {
            resetMatrix();
        }
    }

    private class CopyLocation {

        private float mCopyStartX, mCopyStartY; // 仿制的坐标
        private float mTouchStartX, mTouchStartY; // 开始触摸的坐标
        private float mX, mY; // 当前位置

        private Paint mPaint;

        private boolean isRelocating = true; // 正在定位中
        private boolean isCopying = false; // 正在仿制绘图中

        public CopyLocation(float x, float y) {
            mX = x;
            mY = y;
            mTouchStartX = x;
            mTouchStartY = y;
            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setStrokeWidth(radius);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
        }


        public void updateLocation(float x, float y) {
            mX = x;
            mY = y;
        }

        public void setStartPosition(float x, float y) {
            mCopyStartX = mX;
            mCopyStartY = mY;
            mTouchStartX = x;
            mTouchStartY = y;
        }

        public void drawItSelf(Canvas canvas) {
            mPaint.setStrokeWidth(radius / 4);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(0xaa666666); // 灰色
            DrawUtil.drawCircle(canvas, mX, mY, radius / 2 + radius / 8, mPaint);

            mPaint.setStrokeWidth(radius / 16);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(0xaaffffff); // 白色
            DrawUtil.drawCircle(canvas, mX, mY, radius / 2 + radius / 32, mPaint);

            mPaint.setStyle(Paint.Style.FILL);
            if (!isCopying) {
                mPaint.setColor(0x44ff0000); // 红色
                DrawUtil.drawCircle(canvas, mX, mY, radius / 2, mPaint);
            } else {
                mPaint.setColor(0x44000088); // 蓝色
                DrawUtil.drawCircle(canvas, mX, mY, radius / 2, mPaint);
            }
        }

        /**
         * 判断是否点中
         */
        public boolean isInIt(float x, float y) {
            if ((mX - x) * (mX - x) + (mY - y) * (mY - y) <= radius * radius) {
                return true;
            }
            return false;
        }

    }


    // ===================== api ==============

    public void save() {
        try {
            initCanvas();
            draw(myCanvas, pathStackBackup, false);
            draw(myCanvas, mPathStack, false);
            mGraffitiListener.onSaved(originalBitmap);
            // 释放图片
           /* originalBitmap.recycle();
            galleryBitmap.recycle();*/
        } catch (Throwable e) {//异常 �? error
            e.printStackTrace();
            mGraffitiListener.onError(ERROR_SAVE, "save error");
            return;
        }
        mGraffitiListener.onSaved(originalBitmap);
    }

    /**
     * 清屏
     */
    public void clear() {
        mPathStack.clear();
        pathStackBackup.clear();
        initCanvas();
        invalidate();
    }

    /**
     * 撤销
     */
    public void undo() {
        if (mPathStack.size() > 0) {
            mPathStack.remove(mPathStack.size() - 1);
            initCanvas();
            draw(myCanvas, pathStackBackup, false);
            invalidate();
        } else if (pathStackBackup.size() > 0) {
            pathStackBackup.remove(pathStackBackup.size() - 1);
            initCanvas();
            draw(myCanvas, pathStackBackup, false);
            invalidate();
        }
    }

    /**
     * 是否有修改
     */
    public boolean isModified() {
        return mPathStack.size() != 0 || pathStackBackup.size() != 0;
    }

    /**
     * 居中图片
     */
    public void centrePic() {
        if (scale > 1) {
            new Thread(new Runnable() {
                boolean isScaling = true;

                public void run() {
                    do {
                        scale -= 0.2f;
                        if (scale <= 1) {
                            scale = 1;
                            isScaling = false;
                        }
                        judgePosition();
                        postInvalidate();
                        try {
                            Thread.sleep(timeSpan / 2);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } while (isScaling);

                }
            }).start();
        } else if (scale < 1) {
            new Thread(new Runnable() {
                boolean isScaling = true;

                public void run() {
                    do {
                        scale += 0.2f;
                        if (scale >= 1) {
                            scale = 1;
                            isScaling = false;
                        }
                        judgePosition();
                        postInvalidate();
                        try {
                            Thread.sleep(timeSpan / 2);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } while (isScaling);
                }
            }).start();
        }
    }

    /**
     * 只绘制原图
     *
     * @param justDrawOriginal
     */
    public void setJustDrawOriginal(boolean justDrawOriginal) {
        isJustDrawOriginal = justDrawOriginal;
        invalidate();
    }

    public boolean isJustDrawOriginal() {
        return isJustDrawOriginal;
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    public int getColor() {
        return color;
    }

    public void setScale(float scale) {
        this.scale = scale;
        judgePosition();
        invalidate();
    }

    public float getScale() {
        return scale;
    }

    public void setPen(Pen pen) {
        mPen = pen;
    }

    public Pen getPen() {
        return mPen;
    }

    public void setShape(Shape shape) {
        mShape = shape;
    }

    public Shape getShape() {
        return mShape;
    }

    public void setTransX(float transX) {
        this.transX = transX;
        judgePosition();
        invalidate();
    }

    public float getTransX() {
        return transX;
    }

    public void setTransY(float transY) {
        this.transY = transY;
        judgePosition();
        invalidate();
    }

    public float getTransY() {
        return transY;
    }
}