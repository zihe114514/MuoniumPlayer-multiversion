package today.opai.api.impl;

import com.muoniumplayer.render.skija.SkijaGfx;
import com.muoniumplayer.render.skija.SkijaImages;
import today.opai.api.interfaces.render.GLStateManager;

/**
 * 绘制状态门面的 Skia 实现。
 *
 * <p>这个接口有 40 个方法、46 个文件在用，形状上是一套 OpenGL 1.x 固定管线的状态机。
 * Skia 没有全局绘制状态：颜色、混合方程都挂在 Paint 上，变换和裁剪是 canvas 的栈。
 * 所以这些方法分成三类，每一类的处理方式不同，注释里逐条说明理由，
 * 免得以后有人看到一堆空方法以为是没写完。
 *
 * <h2>一、有精确对应物</h2>
 * 变换（{@code translate/scale/rotate}）、两个状态栈（{@code pushMatrix/pushAttrib}）、
 * 当前颜色（{@code color/resetColor}）、混合方程（{@code blendFunc}）、
 * 纹理绑定（{@code bindTexture}）。这些如实转发到 {@link SkijaGfx}。
 *
 * <h2>二、受控空实现</h2>
 * 开关类调用：{@code enableBlend/disableAlpha/enableTexture2D/enableDepth/enableCull/
 * enableLighting/shadeModel/depthMask/colorMask/alphaFunc/clearDepth} 等。
 *
 * <p>这些不是「懒得实现」，而是<b>在 Skia 模型下不存在对应概念</b>：
 * <ul>
 *   <li>混合永远是开的（由 Paint 的 BlendMode 决定方程），没有「关掉混合」这回事；</li>
 *   <li>alpha test 是固定管线为了省下混合而做的阈值丢弃，Skia 直接做正确的混合，不需要；</li>
 *   <li>「纹理开关」是固定管线在「用纹理」和「用顶点色」之间切换，
 *       Skia 里画图像就是 drawImage、画纯色就是 drawRect，从调用点上就分开了；</li>
 *   <li>深度、剔除、光照都是 3D 管线的东西，这是一个 2D 界面层，从来没用上；</li>
 *   <li>颜色遮罩与深度遮罩，旧代码里只有模板裁剪那套机器在用，
 *       而模板裁剪已经整体换成 canvas 的裁剪栈（见 {@code SkijaClip}）。</li>
 * </ul>
 * 把它们做成空实现而不是删掉，是为了不动那 46 个调用方——那属于业务代码。
 *
 * <h2>三、真的会动资源</h2>
 * {@code generateTexture} / {@code deleteTexture}。纹理的<b>生命周期</b>仍然是真实的 GL 对象：
 * 图片解码上传这条链路没有旧管线的包袱，重写它只会凭空引入 bug，
 * 所以保留，由 Skia 借用（见 {@link SkijaImages}）。
 */
public class GLStateManagerImpl implements GLStateManager {

    // ================= 一、有精确对应物 =================

    @Override
    public void pushMatrix() {
        SkijaGfx.pushMatrix();
    }

    @Override
    public void popMatrix() {
        SkijaGfx.popMatrix();
    }

    /**
     * 对应 {@code glPushAttrib(GL_ALL_ATTRIB_BITS)}：保存的是绘制状态，<b>不含矩阵</b>。
     * 所以这里只压颜色与混合模式，不去动 canvas 的 save 栈——
     * 两个栈在 GL 里是独立的，业务代码确实有交错 push/pop 的写法，
     * 合并成一个栈会让变换错乱。
     */
    @Override
    public void pushAttrib() {
        SkijaGfx.pushAttrib();
    }

    @Override
    public void popAttrib() {
        SkijaGfx.popAttrib();
    }

    @Override
    public void resetColor() {
        SkijaGfx.resetColor();
    }

    /** z 分量丢弃：这是一个 2D 界面层，画布没有深度。 */
    @Override
    public void translate(double x, double y, double z) {
        SkijaGfx.translate(x, y);
    }

    @Override
    public void scale(double x, double y, double z) {
        SkijaGfx.scale(x, y);
    }

    /**
     * 绕任意坐标轴旋转。
     *
     * <p>绕 z 轴（{@code (angle, 0, 0, 1)}）是绝大多数调用形式，在 2D 画布上有精确对应物。
     * 绕 x/y 轴是真正的三维旋转——封面流的封面翻转要用——Skia 的 canvas 变换本身是 4×4 的，
     * 支持透视除法，所以这两种也能如实表达，不需要另搭一套投影管线。
     *
     * <p>只处理「单一坐标轴」的情形：业务代码里没有绕斜轴旋转的调用，
     * 真出现了也宁可忽略，而不是硬套一个近似值画出错误的画面。
     */
    @Override
    public void rotate(float angle, float x, float y, float z) {
        if (z != 0f && x == 0f && y == 0f) {
            SkijaGfx.rotateZ(z > 0 ? angle : -angle);
        } else if (y != 0f && x == 0f && z == 0f) {
            SkijaGfx.rotateY(y > 0 ? angle : -angle);
        } else if (x != 0f && y == 0f && z == 0f) {
            SkijaGfx.rotateX(x > 0 ? angle : -angle);
        }
    }

    @Override
    public void color(float r, float g, float b, float a) {
        SkijaGfx.setColor(r, g, b, a);
    }

    @Override
    public void blendFunc(int srcFactor, int dstFactor) {
        SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(srcFactor, dstFactor));
    }

    /**
     * 分离式混合。Skia 的 {@code BlendMode} 不区分颜色与 alpha 两套因子，
     * 所以只按颜色那一对来映射。旧代码里 alpha 那一对清一色是
     * {@code (ONE, ONE_MINUS_SRC_ALPHA)} 或 {@code (ONE, ZERO)}，
     * 在预乘 alpha 的画布上这两者与 {@code SRC_OVER} 的结果一致。
     */
    @Override
    public void tryBlendFuncSeparate(int srcFactor, int dstFactor, int srcFactorAlpha, int dstFactorAlpha) {
        SkijaGfx.setBlendMode(SkijaGfx.fromGlBlendFunc(srcFactor, dstFactor));
    }

    /**
     * 记下「当前纹理」。不真的发 {@code glBindTexture}：Skia 自己管纹理单元，
     * 在它的绘制序列中间插入裸 GL 绑定会让它的状态缓存失真，画面随机出错
     * （S1 阶段实测踩过同类问题）。
     */
    @Override
    public void bindTexture(int texture) {
        SkijaGfx.setBoundTexture(texture);
    }

    // ================= 二、受控空实现 =================
    // 详见类注释。这些概念在 Skia 的绘制模型里不存在，故意什么都不做。

    /** alpha test：固定管线用阈值丢弃像素来代替混合。Skia 直接做正确的混合。 */
    @Override
    public void alphaFunc(int func, float ref) {
    }

    /** 顶点色插值模式。Skia 的渐变由 Shader 表达，与此无关。 */
    @Override
    public void shadeModel(int mode) {
    }

    /** 固定管线的矩阵模式选择。Skia 只有一个 canvas 变换。 */
    @Override
    public void matrixMode(int mode) {
    }

    /**
     * 把当前矩阵重置成单位矩阵。
     *
     * <p>这里必须是空实现而不是 {@code canvas.resetMatrix()}：宿主层在每帧开头已经把
     * GUI 缩放压进了 canvas 变换，真的重置会把整帧的坐标系抹掉（界面会缩到左上角一小块）。
     * 旧代码调用它是为了配合下面的 {@code ortho} 自己搭一套投影，那套东西已经没有了。
     */
    @Override
    public void loadIdentity() {
    }

    /** 正交投影。坐标系由宿主层统一设定，业务层不再自己搭投影。 */
    @Override
    public void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
    }

    /**
     * 清缓冲。
     *
     * <p>空实现是刻意的：旧代码调用它的场合是「我刚绑了自己的离屏帧缓冲，先清干净」。
     * 离屏帧缓冲已经整体换成 {@code canvas.saveLayer}，图层天生是透明的，不需要清。
     * 如果这里真去 {@code clear}，清掉的会是<b>整帧</b>的内容——界面会整片消失。
     */
    @Override
    public void clear(int mask) {
    }

    @Override
    public void clearColor(float r, float g, float b, float a) {
    }

    @Override
    public void clearDepth(double depth) {
    }

    /** 视口由宿主层按窗口尺寸设定，业务层不再改。 */
    @Override
    public void viewport(int x, int y, int width, int height) {
    }

    /** 旧代码里只有模板裁剪那套机器用它，模板裁剪已换成 canvas 裁剪栈。 */
    @Override
    public void colorMask(boolean r, boolean g, boolean b, boolean a) {
    }

    @Override
    public void depthMask(boolean flag) {
    }

    /** 多纹理单元由 Skia 内部分配。 */
    @Override
    public void setActiveTexture(int textureUnit) {
    }

    /** 显示列表是固定管线的命令录制，已由 Skia 的 TextBlob / Picture 取代。 */
    @Override
    public void callList(int list) {
    }

    @Override
    public void enableBlend() {
    }

    @Override
    public void disableBlend() {
    }

    @Override
    public void enableAlpha() {
    }

    @Override
    public void disableAlpha() {
    }

    @Override
    public void enableTexture2D() {
    }

    @Override
    public void disableTexture2D() {
    }

    @Override
    public void enableDepth() {
    }

    @Override
    public void disableDepth() {
    }

    @Override
    public void enableCull() {
    }

    @Override
    public void disableCull() {
    }

    @Override
    public void enableLighting() {
    }

    @Override
    public void disableLighting() {
    }

    @Override
    public void enableColorMaterial() {
    }

    // ================= 三、真的会动资源 =================

    /**
     * 删除 GL 纹理。
     *
     * <p>先让 Skia 侧指向这个纹理的视图作废再删，顺序反了就是悬垂引用：
     * Skia 会拿一个已经不存在的纹理名去采样。
     */
    @Override
    public void deleteTexture(int texture) {
        SkijaImages.invalidate(texture);
        com.mojang.renderpearl.backend.opengl.GlStateManager._deleteTexture(texture);
    }

    @Override
    public int generateTexture() {
        return com.mojang.renderpearl.backend.opengl.GlStateManager._genTexture();
    }
}
