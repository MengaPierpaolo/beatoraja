package bms.player.beatoraja.play.bga;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

import bms.player.beatoraja.play.BMSPlayer;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Gdx2DPixmap;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.bytedeco.javacv.*;

import static bms.player.beatoraja.skin.SkinProperty.*;

/**
 * ffmpegを使用した動画表示用クラス
 * 
 * @author exch
 */
public class FFmpegProcessor implements MovieProcessor {

	// TODO フレームレートが違う場合がある

	/**
	 * 現在表示中のフレームのTexture
	 */
	private Texture showingtex;
	/**
	 * 動画のfps
	 */
	private double fps;

	private int fpsd = 1;

	private FFmpegFrameGrabber grabber;

	private Pixmap pixmap;
	/**
	 * 現在表示中のPixmap
	 */
	private Pixmap showing;

	private MovieSeekThread movieseek;

	private BMSPlayer player;
	
	private ShaderProgram shader;

	public FFmpegProcessor(int fpsd) {
		this.fpsd = fpsd;
	}

	public void setBMSPlayer(BMSPlayer player) {
		this.player = player;
	}

	@Override
	public void create(String filepath) {
		grabber = new FFmpegFrameGrabber(filepath);
		try {
			grabber.start();
		} catch (Throwable e) {
			e.printStackTrace();
		}
	}

	@Override
	public Texture getFrame() {
		if (showing != pixmap) {
			showing = pixmap;
			if (showingtex != null) {
				showingtex.dispose();
			}
			if (pixmap != null) {
				showingtex = new Texture(pixmap);
			} else {
				showingtex = null;
			}
		}
		return showingtex;
	}

	class MovieSeekThread extends Thread {

		public boolean stop = false;
		public boolean restart = false;
		public boolean loop = false;

		public void run() {
			try {
				Logger.getGlobal().info(
						"decode開始 - fps : " + grabber.getFrameRate() + " format : " + grabber.getFormat() + " size : "
								+ grabber.getImageWidth() + " x " + grabber.getImageHeight()
								+ " length (frame / time) : " + grabber.getLengthInFrames() + " / "
								+ grabber.getLengthInTime());
				fps = grabber.getFrameRate();
				if (fps > 240) {
					// フレームレートが大きすぎる場合は手動で修正(暫定処置)
					fps = 30;
				}
				final long[] nativeData = new long[] { 0, grabber.getImageWidth(), grabber.getImageHeight(),
						Gdx2DPixmap.GDX2D_FORMAT_RGB888 };
				long start = player != null ? player.getNowTime() - player.getTimer()[TIMER_PLAY] : (System.nanoTime() / 1000000);
				int framecount = 0;
				Frame frame = null;
				for (;;) {
					final long time = (player != null ? player.getNowTime() - player.getTimer()[TIMER_PLAY] : (System.nanoTime() / 1000000)) - start;
					if (time >= framecount * 1000 / fps) {
						while (time >= framecount * 1000 / fps || framecount % fpsd != 0) {
							frame = grabber.grabImage();
							framecount++;
						}
						if (frame == null) {
							if(loop) {
								restart = true;
							} else {
								try {
									sleep(3600000);
								} catch (InterruptedException e) {

								}								
							}
						} else if (frame.image != null && frame.image[0] != null) {
							try {
								Gdx2DPixmap pixmapData = new Gdx2DPixmap((ByteBuffer) frame.image[0], nativeData);
								pixmap = new Pixmap(pixmapData);
								// System.out.println("movie pixmap created : "
								// + time);
							} catch (Throwable e) {
								pixmap = null;
								throw new GdxRuntimeException("Couldn't load pixmap from image data", e);
							}
						}
					} else {
						final long sleeptime = (long) (framecount * 1000 / fps - time + 1);
						if (sleeptime > 0) {
							try {
								sleep(sleeptime);
							} catch (InterruptedException e) {

							}
						}
					}
					if (restart) {
						restart = false;
						grabber.start();
//						grabber.restart();
						start = player != null ? player.getNowTime() - player.getTimer()[TIMER_PLAY] : (System.nanoTime() / 1000000);
						framecount = 0;
//						System.out.println("movie restart - starttime : " + start);
					}
					if (stop) {
						break;
					}
				}
			} catch (Throwable e) {
				e.printStackTrace();
			} finally {
				try {
					grabber.stop();
				} catch (Throwable e) {
					e.printStackTrace();
				}
			}

		}
	}
	
	public ShaderProgram getShader() {
		if(shader == null) {
			String vertex = "attribute vec4 " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" //
					+ "attribute vec4 " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" //
					+ "attribute vec2 " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" //
					+ "uniform mat4 u_projTrans;\n" //
					+ "varying vec4 v_color;\n" //
					+ "varying vec2 v_texCoords;\n" //
					+ "\n" //
					+ "void main()\n" //
					+ "{\n" //
					+ "   v_color = " + ShaderProgram.COLOR_ATTRIBUTE + ";\n" //
					+ "   v_texCoords = " + ShaderProgram.TEXCOORD_ATTRIBUTE + "0;\n" //
					+ "   gl_Position =  u_projTrans * " + ShaderProgram.POSITION_ATTRIBUTE + ";\n" //
					+ "}\n";

			String fragment = "#ifdef GL_ES\n" //
					+ "#define LOWP lowp\n" //
					+ "precision mediump float;\n" //
					+ "#else\n" //
					+ "#define LOWP \n" //
					+ "#endif\n" //
					+ "varying LOWP vec4 v_color;\n" //
					+ "varying vec2 v_texCoords;\n" //
					+ "uniform sampler2D u_texture;\n" //
					+ "void main()\n"//
					+ "{\n" //
					+ "    vec4 c4 = texture2D(u_texture, v_texCoords);\n"
					+ "gl_FragColor = v_color * vec4(c4.b, c4.g, c4.r, c4.a);\n" + "}";
			shader = new ShaderProgram(vertex, fragment);			
		}
		return shader;
	}

	@Override
	public void dispose() {
		stop();
		try {
			grabber.release();
		} catch (Throwable e) {

		}
		if (showingtex != null) {
			showingtex.dispose();
		}
		
		if(shader != null) {
			try {
				shader.dispose();				
			} catch(Throwable e) {
				
			}
			shader = null;
		}
	}

	public void play(boolean loop) {
		if (movieseek != null) {
			synchronized (movieseek) {
				movieseek.loop = loop;
				movieseek.restart = true;
				movieseek.interrupt();				
			}
		} else {
			movieseek = new MovieSeekThread();
			movieseek.loop = loop;
			movieseek.start();
		}
	}

	public void stop() {
		if (movieseek != null) {
			synchronized (movieseek) {
				movieseek.stop = true;
				movieseek.interrupt();				
				movieseek = null;				
			}
		}
	}

}
