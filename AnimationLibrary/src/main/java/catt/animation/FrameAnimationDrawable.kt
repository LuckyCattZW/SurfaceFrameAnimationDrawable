package catt.animation

import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.Log.e
import android.view.*
import java.util.*
import kotlin.collections.ArrayList
import android.graphics.drawable.BitmapDrawable
import android.os.*
import android.util.Log
import catt.animation.bean.AnimatorState
import catt.animation.callback.OnAnimationCallback
import catt.animation.component.IBitmapComponent
import catt.animation.component.ICanvasComponent
import catt.animation.controller.IAnimationController
import catt.animation.enums.AnimatorType
import catt.animation.enums.ThreadPriority
import catt.animation.handler.IHandlerThread
import catt.animation.handler.AsyncHandler
import catt.animation.loader.IToolView
import catt.animation.loader.ILoaderLifecycle
import catt.animation.loader.SurfaceViewTool
import catt.animation.loader.TextureViewTool
import java.io.File
import java.io.IOException

/**
 * <h3>帧布局动画</h3>
 * <p>
 *     采用 SurfaceView 与 TextureView进行图片加载帧动画
 * </p>
 */
class FrameAnimationDrawable
private constructor(
    private val priority: Int
) : IAnimationController, ILoaderLifecycle, IBitmapComponent,
    ICanvasComponent {

    private val _TAG: String by lazy { FrameAnimationDrawable::class.java.simpleName }

    /**
     * 构造函数中进行初始化
     * @see SurfaceViewTool
     * @see TextureViewTool
     */
    private lateinit var toolView: IToolView

    private val scaleConfig: ScaleConfig by lazy { ScaleConfig() }

    override val paint: Paint by lazy { generatedBasePaint() }

    override val options: BitmapFactory.Options by lazy { generatedOptions() }

    /**
     * 异步执行线程
     */
    private val handlerThread: IHandlerThread by lazy { AsyncHandler(priority, handlerRunnable) }

    /**
     * 存储帧动画集合
     */
    private val animationList: MutableList<AnimatorState> by lazy { Collections.synchronizedList(ArrayList<AnimatorState>()) }

    override var ownInBitmap: Bitmap? = null

    /**
     * 记录帧动画集合位置
     */
    private var position: Int = 0

    /**
     * 记录帧动画集合重复次数
     */
    private var repeatPosition: Int = 0

    /**
     * 动画监听
     */
    private var callback: OnAnimationCallback? = null

    /**
     * 判断是否开始动画
     */
    private var isOperationStart: Boolean = false



    /**
     * 检查是否到达重复次数
     */
    private val checkRepeatCountLoops: Boolean
        get() = when (repeatCount) {
            INFINITE -> true
            else -> repeatPosition <= repeatCount
        }

    /**
     * @param surfaceView:SurfaceView <p>必填项目,采用SurfaceView进行帧动画加载</p>
     *
     * @param zOrder:Boolean <p>选填项,
     * zOrder 如果是true, 那么帧动画会在所有 View hierachy 顶部进行显示，并且透明显示底部所有View以及图片;
     * zOrder 如果是false, 那么帧动画会在所有 View hierachy 底部进行显示, 底部为黑色画布
     * </p>
     * @see SurfaceView.setZOrderOnTop(onTop:Boolean)
     * @see SurfaceView.setZOrderMediaOverlay(isMediaOverlay:Boolean)
     *
     * @param priority:Int <p>选填项, 可以设置线程优先等级，可以在{@link ThreadPriority}查看具体参数</p>
     *
     * @see ThreadPriority
     */
    constructor(surfaceView: SurfaceView,
                zOrder:Boolean = false,
                priority: Int = ThreadPriority.PRIORITY_DEFAULT) : this(priority) {
        toolView = SurfaceViewTool(surfaceView, zOrder, callback = this)
    }

    /**
     * @param textureView:TextureView <p>必填项目,采用TextureView进行帧动画加载</p>
     *
     * @param priority:Int <p>选填项, 可以设置线程优先等级，可以在{@link ThreadPriority}查看具体参数</p>
     *
     * @see ThreadPriority
     */
    constructor(textureView: TextureView,
                priority: Int = ThreadPriority.PRIORITY_DEFAULT) : this(priority) {
        toolView = TextureViewTool(textureView, callback = this)
    }

    /**
     * 触发生命周期onPause() 会导致 surfaceView触发 surfaceDestroyed(holder:SurfaceHolder) -> close()
     *
     */
    override fun release() {
        cancel()
        animationList.clear()
        toolView.onRelease()
        callback?.onRelease()
    }

    override fun cancel() {
        if (isOperationStart) {
            pause()
            isOperationStart = false
            position = 0
            repeatPosition = 0
            ownInBitmap = null
            //TODO 此处睡眠应改用协程(CoroutineScope)进行挂起处理
            Thread.sleep(64L)
            toolView.cleanCanvas()
            callback?.onCancel()
        }
    }


    /**
     * 触发 {@link SurfaceHolder.Callback.surfaceDestroyed()} 将导致触发该方法
     */
    override fun pause() {
        handlerThread.setPaused(true)
        handlerThread.terminate()
        callback?.onPause()
    }

    /**
     * 触发 {@link SurfaceHolder.Callback.surfaceCreated()} 将导致触发该方法
     */
    override fun restore() {
        handlerThread.setPaused(false)
        handlerThread.terminate()
        handlerThread.play()
    }

    @Throws(IllegalArgumentException::class)
    override fun start() {
        if (animationList.size == 0) throw IllegalArgumentException("Animation size must be > 0")
        when{
            !isOperationStart && handlerThread.isPaused -> {
                ownInBitmap = null
                repeatPosition = 0
                isOperationStart = true
                animationList.sort()
                restore()
                callback?.onStart()
            }
            isOperationStart && handlerThread.isPaused ->{
                restore()
            }
        }
    }

    override fun onLoaderCreated() {
        e(_TAG, "onLoaderCreated")
        if(isOperationStart) {
            restore()
        }
    }

    override fun onLoaderChanged() {
        e(_TAG, "onLoaderChanged")
    }

    override fun onLoaderDestroyed(): Boolean {
        e(_TAG, "onLoaderDestroyed")
        if(isOperationStart) {
            pause()
        }
        return true
    }

    /**
     * 设置图片缩放类型
     */
    fun setScaleType(@ScaleConfig.SC.ScaleType scaleType:Int){
        scaleConfig.scaleType = scaleType
    }

    /**
     * 设置动画监听
     */
    fun setOnAnimationCallback(callback: SimpleOnAnimationCallback) {
        this.callback = callback
    }

    /**
     * 设置动画监听
     */
    fun setOnAnimationCallback(callback: OnAnimationCallback) {
        this.callback = callback
    }

    /**
     * 设置循环次数
     * @see FrameAnimationDrawable.INFINITE 无限循环
     */
    var repeatCount: Int = 0
        set(count) {
            field = when {
                count < 0 -> INFINITE
                else -> count
            }
        }

    /**
     * 设置循环模式
     *  @see FrameAnimationDrawable.RESTART 从首部循环
     *  @see FrameAnimationDrawable.REVERSE 首尾连接式循环
     */
    var repeatMode: Int = RESTART
        set(mode) {
            field = when (mode) {
                RESTART -> RESTART
                REVERSE -> REVERSE
                else -> throw IllegalArgumentException("Please use the correct parameters.")
            }
        }

    /**
     * 添加帧动画资源
     * @param resId: Int 资源id
     * @param duration: Long 延时时间 默认0ms
     */
    fun addFrame(resId: Int, duration: Long = 0L) {
        animationList.add(
            AnimatorState(
                SystemClock.elapsedRealtime(),
                resId,
                duration
            )
        )
    }

    /**
     * 通过Resources.class 反射资源文件,添加帧动画
     *
     * @param resName: String 资源名称
     * @param resType: String 资源类型
     * @param resPackageName: String 资源所在包的包名
     * @param duration: Long 延时时间 默认0ms
     *
     * @see Resources.getIdentifier(String name, String defType, String defPackage)
     */
    fun addFrame(resName: String, resType: String, resPackageName: String, duration: Long = 0L) {
        animationList.add(
            AnimatorState(
                SystemClock.elapsedRealtime(),
                resName,
                resType,
                resPackageName,
                duration
            )
        )
    }

    /**
     * 从本地图片文件路径,添加帧动画
     * @param path: String 文件路径 路径
     * @param isAssetResource: Boolean 是否是资产文件
     * @param duration: Long 延时时间 默认0ms
     */
    fun addFrame(path: String, isAssetResource: Boolean, duration: Long = 0L) {
        animationList.add(AnimatorState(SystemClock.elapsedRealtime(), path, isAssetResource, duration))
    }

    private fun scanAnimatorState(): AnimatorState {
        if (animationList.size == position) {
            when (repeatMode) {
                REVERSE -> animationList.reverse()
                RESTART -> animationList.sort()
            }
            position = 0
            ++repeatPosition
        }
        val o: AnimatorState = animationList[position]
        ++position
        return o
    }

    private fun getBitmap(resources: Resources, bean: AnimatorState):Bitmap? {
        ownInBitmap = when (bean.animatorType) {
            AnimatorType.RES_ID -> decodeBitmapReal(resources, bean.resId)
            AnimatorType.IDENTIFIER -> {
                val identifier: Int = resources.getIdentifier(bean.resName, bean.resType, bean.resPackageName)
                if (identifier > 0) decodeBitmapReal(resources, identifier)
                else null
            }
            AnimatorType.CACHE -> {
                if(bean.isAssetResource) decodeBitmapReal(toolView.context?.assets, bean.path)
                else decodeBitmapReal(bean.path)
            }
            else -> null
        }
        return ownInBitmap
    }

    private val handlerRunnable: Runnable = Runnable {
        when {
            toolView.context == null -> {
                e(_TAG, "Context is null, terminate frame animation drawable.")
                handlerThread.terminate()
                return@Runnable
            }
            !isOperationStart -> {
                e(_TAG, "Terminate frame animation drawable.")
                handlerThread.terminate()
                return@Runnable
            }
            !checkRepeatCountLoops -> {
                e(_TAG, "repeat count finished.")
                handlerThread.terminate()
                return@Runnable
            }
            !toolView.isVisible -> {
                e(_TAG, "This 'SurfaceView' is unvisible.")
                handlerThread.terminate()
                handlerThread.play(618L)
                return@Runnable
            }
            !toolView.isMeasured -> {
                e(_TAG, "The 'SurfaceView' has not yet been measured.")
                handlerThread.terminate()
                handlerThread.play(618L)
                return@Runnable
            }
        }
        val bean: AnimatorState = scanAnimatorState()
        try {
            if (bean.animatorType != AnimatorType.UNKNOW) {
                getBitmap(toolView.context!!.resources!!, bean)?.apply bitmap@{
                    val matrix = scaleConfig.configureDrawMatrix(toolView.view!!, this@bitmap)
                    toolView.lockCanvas()?.apply {
                        drawSurfaceAnimationBitmap(this@bitmap, matrix, paint)
                        toolView.unlockCanvasAndPost(this)
                    }
                }
            }
        } catch (ex:NullPointerException){
            ex.printStackTrace()
        } finally {
            handlerThread.play(bean.duration)
        }
    }




    open class SimpleOnAnimationCallback : OnAnimationCallback {
        override fun restore() {

        }

        override fun onStart() {
        }

        override fun onPause() {
        }

        override fun onCancel() {
        }

        /**
         * 触发生命周期onPause() 会导致 surfaceView触发 surfaceDestroyed(holder:SurfaceHolder)
         */
        override fun onRelease() {
        }
    }

    companion object {
        /**
         * 执行次数无线循环
         */
        const val INFINITE = -1
        /**
         * 执行方式，从首部执行
         */
        const val RESTART = 1
        /**
         * 执行方式，首尾相接式执行
         */
        const val REVERSE = 2
    }
}