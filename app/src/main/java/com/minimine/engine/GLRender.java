package com.minimine.engine;

import android.view.MotionEvent;
import android.content.Context;
import android.opengl.GLSurfaceView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.opengl.GLES30;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.ArrayList;
import android.graphics.Bitmap;
import android.content.res.AssetManager;
import java.io.IOException;
import java.io.InputStream;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.egl.EGLConfig;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Comparator;
import com.minimine.MundoActivity;
import android.opengl.Matrix;
import android.opengl.GLUtils;
import java.nio.ShortBuffer;
import java.io.File;
import android.os.Environment;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.DataOutputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.BufferedInputStream;
import android.os.Handler;
import android.os.Looper;

public class GLRender implements GLSurfaceView.Renderer {
    public final Context contexto;
    public final GLSurfaceView tela;
    public float[] projMatriz = new float[16];
    public float[] viewMatriz = new float[16];
    public float[] vpMatriz = new float[16];
	
    public Player camera = new Player();

    public String modo = "alpha";

    public ExecutorService executor = Executors.newFixedThreadPool(4);

	public float pesoConta = 0f;

	public boolean debug = false, gc = false, gravidade = true, trava = true;
	
	// chunks:
	public Mundo mundo;
	public int seed;
	public String nome, tipo, ciclo;
	public String pacoteTex;
	public int shaderPrograma;
    public int lidarvPMatriz;
    public int lidarPosicao;
    public int lidarTexCoord;
    public int lidarTexturas;
    public int lidarLuzDirecao;
	public int lidarLuzIntensidade;
    public int lidarNormal;
    public float[] luzDirecao = {0.5f, 1.0f, 0.5f};
	public float luz = 0.93f;
	public int chunksPorVez = 1;
	
	// céu:
	public float tempo = 0.40f;
	public float tempoVelo = 0.00001f;
	
	public int shaderProgramaCeu;
	public int viewLocCeu;
	public int projLocCeu;
	public int tempoLocCeu;
	public int[] vboCeu;
	public int[] vaoCeu;
	public FloatBuffer ceuBuffer;
	
	// hitbox
	public FloatBuffer hitboxBuffer;
	public int programaHitbox;
	
	// gravidade
	public Handler gravidadeLoop;
	public Runnable tarefaGravidade;

	public void renderizar() {
		GLES30.glUseProgram(shaderPrograma);
		GLES30.glUniformMatrix4fv(lidarvPMatriz, 1, false, vpMatriz, 0);
		GLES30.glUniform1f(lidarLuzIntensidade, luz);
		GLES30.glUniform3fv(lidarLuzDirecao, 1, luzDirecao, 0);
		
		for(Map.Entry<String, Bloco[][][]> entry : mundo.chunksAtivos.entrySet()) {
			final String chave = entry.getKey();

			if(mundo.chunksAlterados.containsKey(chave) && mundo.chunksAlterados.get(chave)) {
				final Bloco[][][] chunk = entry.getValue();
				executor.submit(new Runnable() {
						public void run() {
							final Map<Integer, List<float[]>> dados = mundo.calculoVBO(chunk);
							tela.queueEvent(new Runnable() {
									public void run() {
										final List<VBOGrupo> grupos = gerarVBO(dados);
										mundo.chunkVBOs.put(chave, grupos);
									}
								});
						}
					});
				mundo.chunksAlterados.put(chave, false);
			}

			List<VBOGrupo> grupos = mundo.chunkVBOs.get(chave);
			if(grupos == null) continue;

			for(VBOGrupo grupo : grupos) {
				// textura:
				GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grupo.texturaId);
				
				// VBO:
				GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, grupo.vboId);
				GLES30.glVertexAttribPointer(
					lidarPosicao,
					3,
					GLES30.GL_FLOAT,
					false,
					8 * 4,
					0
				);
				GLES30.glEnableVertexAttribArray(lidarPosicao);

				GLES30.glVertexAttribPointer(
					lidarNormal,
					3,
					GLES30.GL_FLOAT,
					false,
					8 * 4,
					3 * 4
				);
				GLES30.glEnableVertexAttribArray(lidarNormal);

				GLES30.glVertexAttribPointer(
					lidarTexCoord,
					2,
					GLES30.GL_FLOAT,
					false,
					8 * 4,
					6 * 4
				);
				GLES30.glEnableVertexAttribArray(lidarTexCoord);

				// IBO:
				GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, grupo.iboId);
				// renderiza:
				GLES30.glDrawElements(
					GLES30.GL_TRIANGLES,
					grupo.vertices,
					GLES30.GL_UNSIGNED_SHORT,
					0
				);

				// desvincula buffers:
				GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);
				GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
			}
		}
	}

	public void iniciarShaders(Context contexto) {
		String shaderVertice =
			"uniform mat4 u_VPMatriz;" +
			"uniform vec3 u_direcaoLuz;" +
			"uniform float u_intensidadeLuz;" +
			"attribute vec4 a_Posicao;" +
			"attribute vec3 a_Normal;" +
			"attribute vec2 a_TexCoord;" +
			"varying vec2 v_TexCoord;" +
			"varying float v_intencidadeLuz;" +
			"void main() {" +
			"gl_Position = u_VPMatriz * a_Posicao;" +
			"v_TexCoord = a_TexCoord;" +
			"v_intencidadeLuz = max(dot(normalize(a_Normal), normalize(u_direcaoLuz)), 0.70) * u_intensidadeLuz;" +
			"}";
		String shaderFragmento =
            "precision mediump float;" +
            "varying vec2 v_TexCoord;" +
            "varying float v_intencidadeLuz;" +
            "uniform sampler2D u_Textura;" +
            "void main() {" +
            "vec4 corTextura = texture2D(u_Textura, v_TexCoord);" +
            "gl_FragColor = vec4(corTextura.rgb * v_intencidadeLuz, corTextura.a);" +
            "}";
		shaderPrograma = ShaderUtils.criarPrograma(shaderVertice, shaderFragmento);
		lidarvPMatriz = GLES30.glGetUniformLocation(shaderPrograma, "u_VPMatriz");
		lidarLuzDirecao = GLES30.glGetUniformLocation(shaderPrograma, "u_direcaoLuz");
		lidarPosicao = GLES30.glGetAttribLocation(shaderPrograma, "a_Posicao");
		lidarNormal = GLES30.glGetAttribLocation(shaderPrograma, "a_Normal");
		lidarTexCoord = GLES30.glGetAttribLocation(shaderPrograma, "a_TexCoord");
		lidarTexturas = GLES30.glGetUniformLocation(shaderPrograma, "u_Textura");
		lidarLuzIntensidade = GLES30.glGetUniformLocation(shaderPrograma, "u_intensidadeLuz");
		
		String vertC =
			"#version 300 es\n" +
			"layout (location = 0) in vec3 aPos;\n" +
			"uniform mat4 u_view;\n" +
			"uniform mat4 u_projecao;\n" +
			"out vec3 TexCoords;\n" +
			"void main() {\n" +
			"TexCoords = aPos;\n" +
			"mat4 view = u_view;\n" +
			"view[3][0] = 0.0;\n" +
			"view[3][1] = 0.0;\n" +
			" view[3][2] = 0.0;\n" +
			"vec4 pos = u_projecao * view * vec4(aPos, 1.0);\n" +
			"gl_Position = pos.xyww;\n" +
			"}";

		String fragC =
			"#version 300 es\n" +
			"precision mediump float;\n" +
			"in vec3 TexCoords;\n" +
			"uniform float u_tempo;\n" +
			"out vec4 FragColor;\n" +
			"void main() {\n" +
			"vec3 diaCor = vec3(0.53, 0.81, 0.98);\n" +
			"vec3 noiteCor = vec3(0.0, 0.0, 0.1);\n" +
			"float t = abs(u_tempo - 0.5) * 2.0;\n" +
			"vec3 ceuCor = mix(diaCor, noiteCor, t);\n" +
			"FragColor = vec4(ceuCor, 1.0);\n" +
			"}";

		shaderProgramaCeu = ShaderUtils.criarPrograma(vertC, fragC);

		viewLocCeu = GLES30.glGetUniformLocation(shaderProgramaCeu, "u_view");
		projLocCeu = GLES30.glGetUniformLocation(shaderProgramaCeu, "u_projecao");
		tempoLocCeu = GLES30.glGetUniformLocation(shaderProgramaCeu, "u_tempo");
		
		float[] verticesCeu = {
			// face tras
			-1.0f,  1.0f, -1.0f,
			-1.0f, -1.0f, -1.0f,
			1.0f, -1.0f, -1.0f,
			1.0f, -1.0f, -1.0f,
			1.0f,  1.0f, -1.0f,
			-1.0f,  1.0f, -1.0f,
			// face esquerda
			-1.0f, -1.0f,  1.0f,
			-1.0f, -1.0f, -1.0f,
			-1.0f,  1.0f, -1.0f,
			-1.0f,  1.0f, -1.0f,
			-1.0f,  1.0f,  1.0f,
			-1.0f, -1.0f,  1.0f,
			// face direita
			1.0f, -1.0f, -1.0f,
			1.0f, -1.0f,  1.0f,
			1.0f,  1.0f,  1.0f,
			1.0f,  1.0f,  1.0f,
			1.0f,  1.0f, -1.0f,
			1.0f, -1.0f, -1.0f,
			// face frente
			-1.0f, -1.0f,  1.0f,
			-1.0f,  1.0f,  1.0f,
			1.0f,  1.0f,  1.0f,
			1.0f,  1.0f,  1.0f,
			1.0f, -1.0f,  1.0f,
			-1.0f, -1.0f,  1.0f,
			// face cima
			-1.0f,  1.0f, -1.0f,
			1.0f,  1.0f, -1.0f,
			1.0f,  1.0f,  1.0f,
			1.0f,  1.0f,  1.0f,
			-1.0f,  1.0f,  1.0f,
			-1.0f,  1.0f, -1.0f,
			// face baixo
			-1.0f, -1.0f, -1.0f,
			-1.0f, -1.0f,  1.0f,
			1.0f, -1.0f, -1.0f,
			1.0f, -1.0f, -1.0f,
			-1.0f, -1.0f,  1.0f,
			1.0f, -1.0f,  1.0f
		};

		ceuBuffer = ByteBuffer.allocateDirect(verticesCeu.length * 4)
			.order(ByteOrder.nativeOrder())
			.asFloatBuffer();
		ceuBuffer.put(verticesCeu);
		ceuBuffer.position(0);

		vaoCeu = new int[1];
		vboCeu = new int[1];
		GLES30.glGenVertexArrays(1, vaoCeu, 0);
		GLES30.glGenBuffers(1, vboCeu, 0);

		GLES30.glBindVertexArray(vaoCeu[0]);
		
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboCeu[0]);
	
		GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verticesCeu.length * 4, ceuBuffer, GLES30.GL_STATIC_DRAW);
		
		GLES30.glEnableVertexAttribArray(0);
		GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 3 * 4, 0);
		GLES30.glBindVertexArray(0);
		String vertHitboxC =
			"uniform mat4 uMVPMatriz;" +
			"attribute vec3 aPosicao;" +
			"void main() {"+
			"gl_Position = uMVPMatriz * vec4(aPosicao, 1.0);"+
			"}";

		String fragHitboxC =
			"precision mediump float;" +
			"uniform vec4 uCor;" +
			"void main() {"+
			"gl_FragColor = uCor;"+
			"}";

		programaHitbox = ShaderUtils.criarPrograma(vertHitboxC, fragHitboxC);
	}
	
	public void renderHitbox() {
		float altura = camera.hitbox[0];
		float largura = camera.hitbox[1];
		float metadeLargura = largura / 2f;
		float minX = camera.posicao[0] - metadeLargura;
		float maxX = camera.posicao[0] + metadeLargura;
		float minY = camera.posicao[1] - 1.5f;
		float maxY = camera.posicao[1] + altura;
		float minZ = camera.posicao[2] - metadeLargura;
		float maxZ = camera.posicao[2] + metadeLargura;
		
		float[] vertices = {
			minX, minY, minZ,  maxX, minY, minZ,
			maxX, minY, minZ,  maxX, minY, maxZ,
			maxX, minY, maxZ,  minX, minY, maxZ,
			minX, minY, maxZ,  minX, minY, minZ,
			minX, maxY, minZ,  maxX, maxY, minZ,
			maxX, maxY, minZ,  maxX, maxY, maxZ,
			maxX, maxY, maxZ,  minX, maxY, maxZ,
			minX, maxY, maxZ,  minX, maxY, minZ,
			minX, minY, minZ,  minX, maxY, minZ,
			maxX, minY, minZ,  maxX, maxY, minZ,
			maxX, minY, maxZ,  maxX, maxY, maxZ,
			minX, minY, maxZ,  minX, maxY, maxZ
		};
		if(hitboxBuffer==null || hitboxBuffer.capacity() < vertices.length) {
			hitboxBuffer = ByteBuffer.allocateDirect(72 * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		}
		hitboxBuffer.clear();
		hitboxBuffer.put(vertices);
		hitboxBuffer.position(0);

		GLES30.glUseProgram(programaHitbox);
		int mvpLidar = GLES30.glGetUniformLocation(programaHitbox, "uMVPMatriz");
		int corLidar = GLES30.glGetUniformLocation(programaHitbox, "uCor");
		int posLidar = GLES30.glGetAttribLocation(programaHitbox, "aPosicao");
		GLES30.glUniformMatrix4fv(mvpLidar, 1, false, vpMatriz, 0);
		GLES30.glUniform4f(corLidar, 1f, 0f, 0f, 1f);
		GLES30.glEnableVertexAttribArray(posLidar);
		GLES30.glVertexAttribPointer(posLidar, 3, GLES30.GL_FLOAT, false, 3 * 4, hitboxBuffer);
		GLES30.glDrawArrays(GLES30.GL_LINES, 0, vertices.length / 3);
		GLES30.glDisableVertexAttribArray(posLidar);
	}
	
	public void atualizarTempo() {
		if(tempo < 1.1f) {
			tempo += tempoVelo;
		} else {
			tempo = 0.0f;
		}
		luz = 1.0f - Math.abs(tempo - 0.5f) * 1.0f;
		
		if(tempo >= 0.3f && tempo <= 0.7) ciclo = "tarde";
		else if(tempo >= 0.8) ciclo = "noite";
		else if(tempo <= 0.2) ciclo = "dia";
		
		GLES30.glUseProgram(shaderProgramaCeu);
		GLES30.glUniformMatrix4fv(viewLocCeu, 1, false, viewMatriz, 0);
		GLES30.glUniformMatrix4fv(projLocCeu, 1, false, projMatriz, 0);
		GLES30.glUniform1f(tempoLocCeu, tempo);
		
		GLES30.glDepthFunc(GLES30.GL_LEQUAL);
		GLES30.glBindVertexArray(vaoCeu[0]);
		GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 36);
		GLES30.glBindVertexArray(0);
		GLES30.glDepthFunc(GLES30.GL_LESS);
	}

	public void limparTexturas() {
		if(mundo.atlasTexturaId != -1) {
			GLES30.glDeleteTextures(1, new int[] { mundo.atlasTexturaId }, 0);
			mundo.atlasTexturaId = -1;
		}
		mundo.atlasUVMapa.clear();
	}

	public List<VBOGrupo> gerarVBO(Map<Integer, List<float[]>> dadosPorTextura) {
		List<VBOGrupo> grupos = new ArrayList<>();

		for(Map.Entry<Integer, List<float[]>> entry : dadosPorTextura.entrySet()) {
			int texturaId = entry.getKey();

			//  calcula total de vertices em todas as listas float[]
			int totalVertices = 0;
			for(float[] arr : entry.getValue()) {
				totalVertices += arr.length / 8;
			}

			// cria buffer de vertices
			FloatBuffer vertBuffer = ByteBuffer
				.allocateDirect(totalVertices * 8 * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();

			for(float[] dados : entry.getValue()) {
				vertBuffer.put(dados);
			}
			vertBuffer.position(0);

			// gera array de indices de sequencia
			ShortBuffer indiceBuffer = ByteBuffer
				.allocateDirect(totalVertices * 2)
				.order(ByteOrder.nativeOrder())
				.asShortBuffer();
			for(short i = 0; i < totalVertices; i++) {
				indiceBuffer.put(i);
			}
			indiceBuffer.position(0);

			// gera IDs de buffer(VBO e IBO)
			int[] bufferIds = new int[2];
			GLES30.glGenBuffers(2, bufferIds, 0);
			int vboId = bufferIds[0];
			int iboId = bufferIds[1];

			// prenche VBO
			GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId);
			GLES30.glBufferData(
				GLES30.GL_ARRAY_BUFFER,
				vertBuffer.capacity() * 4,
				vertBuffer,
				GLES30.GL_STATIC_DRAW
			);

			// prenche IBO
			GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, iboId);
			GLES30.glBufferData(
				GLES30.GL_ELEMENT_ARRAY_BUFFER,
				indiceBuffer.capacity() * 2,
				indiceBuffer,
				GLES30.GL_STATIC_DRAW
			);

			// desvincula VBO(IBO fica associado ao VAO)
			GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
			GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0);

			grupos.add(new VBOGrupo(texturaId, vboId, iboId, totalVertices));
		}
		return grupos;
	}
	
	public boolean pronto = false;
	public int frame;

    public GLRender(Context contexto, GLSurfaceView tela, int seed, String nome, String tipo, String pacoteTex) {
        this.contexto = contexto;
        this.tela = tela;
		this.seed = seed;
		this.nome = nome;
		this.tipo = tipo;
		this.pacoteTex = pacoteTex;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES30.glClearColor(0.40f, 0.65f, 0.85f, 1.0f);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
		
        this.mundo = new Mundo(
		this.tela, this.seed, this.nome,
		this.tipo, this.pacoteTex
		);
		crMundo(this.mundo);
        if(modo.equals("alpha")) this.mundo.RAIO_CARREGAMENTO = 2;
        if(modo.equals("teste")) this.mundo.RAIO_CARREGAMENTO = 1;
        this.iniciarShaders(contexto);
        this.carregarTexturas(contexto);
    }

	@Override
    public void onDrawFrame(GL10 gl) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
		atualizarTempo();
		atualizarChunks();
		
		if(pronto==true) {
			if(frame++ % 1 == 0) atualizarGravidade();
			Matrix.multiplyMM(vpMatriz, 0, projMatriz, 0, viewMatriz, 0);
			renderizar();

			atualizarViewMatriz();
			if(mundo.noChao(camera)) {
				camera.noAr = false;
				pesoConta = 0.1f;
			} else {
				camera.noAr = true;
			}
		}
		if(gc == true) {
			ativarGC();
		}
		if(debug == true) {
			renderHitbox();
		}
    }
	
	public void atualizarGravidade() {
		if(camera.noAr == true && gravidade==true || camera.peso==0) {
			if(pesoConta>=2f) pesoConta = 2f;
			else pesoConta += camera.pesoTotal;
			float novaY = camera.posicao[1] - camera.peso*pesoConta;
			float[] pos = mundo.verificarColisao(camera, camera.posicao[0], novaY, camera.posicao[2]);
			camera.posicao[1] = pos[1];
		}
	}

    public void atualizarChunks() {
		int chunkJogadorX = (int) Math.floor(camera.posicao[0] / mundo.CHUNK_TAMANHO);
		int chunkJogadorZ = (int) Math.floor(camera.posicao[2] / mundo.CHUNK_TAMANHO);

		// remove chunks fora do raio
		Iterator<Map.Entry<String, Bloco[][][]>> iterator = mundo.chunksAtivos.entrySet().iterator();
		while(iterator.hasNext()) {
			Map.Entry<String, Bloco[][][]> entry = iterator.next();
			String chave = entry.getKey();
			String[] partes = chave.split(",");
			int chunkX = Integer.parseInt(partes[0]);
			int chunkZ = Integer.parseInt(partes[1]);

			int deltaX = Math.abs(chunkX - chunkJogadorX);
			int deltaZ = Math.abs(chunkZ - chunkJogadorZ);

			if(deltaX > mundo.RAIO_CARREGAMENTO || deltaZ > mundo.RAIO_CARREGAMENTO) {
				iterator.remove();

				List<VBOGrupo> grupos = mundo.chunkVBOs.remove(chave);
				if(!mundo.chunksModificados.containsKey(chave)) {
					mundo.chunksCarregados.remove(chave);
				}
				if(grupos != null) {
					for(VBOGrupo grupo : grupos) {
						GLES30.glDeleteBuffers(1, new int[]{ grupo.vboId }, 0);
						GLES30.glDeleteBuffers(1, new int[]{ grupo.iboId }, 0);
					}
				}
			}
		}
		
		PriorityQueue<ChunkCandidato> fila = new PriorityQueue<ChunkCandidato>(10, new Comparator<ChunkCandidato>() {
				public int compare(ChunkCandidato a, ChunkCandidato b) {
					return Double.compare(a.distancia, b.distancia);
				}
			});

		for(int x = chunkJogadorX - mundo.RAIO_CARREGAMENTO; x <= chunkJogadorX + mundo.RAIO_CARREGAMENTO; x++) {
			for(int z = chunkJogadorZ - mundo.RAIO_CARREGAMENTO; z <= chunkJogadorZ + mundo.RAIO_CARREGAMENTO; z++) {
				final String chave = x + "," + z;
				if(!mundo.chunksAtivos.containsKey(chave)) {
					double dist = Math.sqrt((x - chunkJogadorX)*(x - chunkJogadorX) + (z - chunkJogadorZ)*(z - chunkJogadorZ));
					fila.offer(new ChunkCandidato(chave, x, z, dist));
				}
			}
		}

		int carregados = 0;
		
		if(MundoActivity.livre >= 10.0 || trava == false) {
			while(!fila.isEmpty() && carregados < this.chunksPorVez) {
				final ChunkCandidato c = fila.poll();
				final String chave = c.chave;
				final Bloco[][][] chunk = mundo.carregarChunk(c.x, c.z);
				mundo.chunksAtivos.put(chave, chunk);
				executor.submit(new Runnable() {
						public void run() {
							final Map<Integer, List<float[]>> dados = mundo.calculoVBO(chunk);
							tela.queueEvent(new Runnable() {
									public void run() {
										List<VBOGrupo> grupos = gerarVBO(dados);
										mundo.chunkVBOs.put(chave, grupos);
									}
								});
						}
					});
				carregados++;
			}
			if(pronto == false) pronto = true;
			MundoActivity.gc = "\n[GC]: livre";
		} else {
			ativarGC();
		}
	}
	
	public static void ativarGC() {
		System.gc();
		Runtime.getRuntime().runFinalization();
		MundoActivity.gc = "\n[GC]: forçando limpeza";
	}

	private static class ChunkCandidato {
		String chave;
		int x, z;
		double distancia;
		ChunkCandidato(String chave, int x, int z, double distancia) {
			this.chave = chave;
			this.x = x;
			this.z = z;
			this.distancia = distancia;
		}
	}

    @Override
    public void onSurfaceChanged(GL10 gl, int horizontal, int lateral) {
        GLES30.glViewport(0, 0, horizontal, lateral);
        float ratio = (float) horizontal / lateral;
        Matrix.perspectiveM(projMatriz, 0, 90, ratio, 0.1f, 1000f);
    }
	
	public void destruir() {
		for(List<VBOGrupo> grupos : mundo.chunkVBOs.values()) {
			for(VBOGrupo grupo : grupos) {
				GLES30.glDeleteBuffers(1, new int[]{grupo.vboId}, 0);
				GLES30.glDeleteBuffers(1, new int[]{grupo.iboId}, 0);
				GLES30.glDeleteTextures(1, new int[]{grupo.texturaId}, 0);
			}
		}
		executor.shutdown();
	}
	
	public void carregarTexturas(Context contexto) {
		HashSet<String> recursosUnicos = new HashSet<>();
		for(String[] tipo : TipoBloco.tipos.values()) {
			// "0" = textura vazia
			if(tipo[0].equals("0"))
				continue;
			for(int face = 0; face < 6; face++) {
				String recurso = tipo[face];
				if(!recurso.equals("-1"))
					recursosUnicos.add(recurso);
			}
		}

		ArrayList<String> listaRecursos = new ArrayList<>(recursosUnicos);
		int numTexturas = listaRecursos.size();
		int atlasCols = (int) Math.ceil(Math.sqrt(numTexturas));
		int atlasRows = (int) Math.ceil((double) numTexturas / atlasCols);

		Bitmap tempBitmap = null;
		try {
			AssetManager assetManager = contexto.getAssets();
			InputStream inputStream = assetManager.open(pacoteTex+listaRecursos.get(0));
			tempBitmap = BitmapFactory.decodeStream(inputStream);
			inputStream.close();
		} catch(IOException e) {
			e.printStackTrace();
		}

		int texV = tempBitmap.getWidth();
		int texH = tempBitmap.getHeight();
		tempBitmap.recycle();

		int atlasV = atlasCols * texV;
		int atlasH = atlasRows * texH;
		Bitmap atlasBitmap = Bitmap.createBitmap(atlasV, atlasH, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(atlasBitmap);
		Paint paint = new Paint();
		paint.setFilterBitmap(false);

		for(int i = 0; i < listaRecursos.size(); i++) {
			String recurso = listaRecursos.get(i);
			int col = i % atlasCols;
			int row = i / atlasCols;
			int x = col * texV;
			int y = row * texH;
			Bitmap bitmap = null;
			try {
				AssetManager assetManager = contexto.getAssets();
				InputStream inputStream = assetManager.open(pacoteTex+recurso);
				bitmap = BitmapFactory.decodeStream(inputStream);
				inputStream.close();
			} catch(IOException e) {
				e.printStackTrace();
			}
			canvas.drawBitmap(bitmap, x, y, paint);
			bitmap.recycle();

			float u_min = (float) x / atlasV;
			float v_min = (float) y / atlasH;
			float u_max = (float) (x + texV) / atlasV;
			float v_max = (float) (y + texH) / atlasH;
			mundo.atlasUVMapa.put(recurso, new float[]{u_min, v_min, u_max, v_max});
		}

		int[] texturaIds = new int[1];
		GLES30.glGenTextures(1, texturaIds, 0);
		mundo.atlasTexturaId = texturaIds[0];
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mundo.atlasTexturaId);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
		GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, atlasBitmap, 0);
		atlasBitmap.recycle();
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
	}
	
	public void colocarBloco() {
		float[] pos = camera.posicao;

		float yaw = camera.yaw;
		float tom = camera.tom;

		float dx = (float) (Math.cos(Math.toRadians(tom)) * Math.cos(Math.toRadians(yaw)));
		float dy = (float) Math.sin(Math.toRadians(tom));
		float dz = (float) (Math.cos(Math.toRadians(tom)) * Math.sin(Math.toRadians(yaw)));

		float maxDist = camera.alcance;

		int mapaX = (int) Math.floor(pos[0]);
		int mapaY = (int) Math.floor(pos[1]);
		int mapaZ = (int) Math.floor(pos[2]);

		float deltaDistX = (dx==0) ? Float.MAX_VALUE : Math.abs(1 / dx);
		float deltaDistY = (dy==0) ? Float.MAX_VALUE : Math.abs(1 / dy);
		float deltaDistZ = (dz==0) ? Float.MAX_VALUE : Math.abs(1 / dz);

		int passoX = dx < 0 ? -1 : 1;
		int passoY = dy < 0 ? -1 : 1;
		int passoZ = dz < 0 ? -1 : 1;

		float ladoDistX = (dx < 0) ? (pos[0] - mapaX) * deltaDistX : (mapaX + 1.0f - pos[0]) * deltaDistX;
		float ladoDistY = (dy < 0) ? (pos[1] - mapaY) * deltaDistY : (mapaY + 1.0f - pos[1]) * deltaDistY;
		float ladoDistZ = (dz < 0) ? (pos[2] - mapaZ) * deltaDistZ : (mapaZ + 1.0f - pos[2]) * deltaDistZ;

		float distanciaPercorrida = 0.0f;
		int hitAxis = -1;

		while(distanciaPercorrida < maxDist) {
			if(ladoDistX <= ladoDistY && ladoDistX <= ladoDistZ) {
				mapaX += passoX;
				distanciaPercorrida = ladoDistX;
				ladoDistX += deltaDistX;
				hitAxis = 0;
			} else if(ladoDistY <= ladoDistZ) {
				mapaY += passoY;
				distanciaPercorrida = ladoDistY;
				ladoDistY += deltaDistY;
				hitAxis = 1;
			} else {
				mapaZ += passoZ;
				distanciaPercorrida = ladoDistZ;
				ladoDistZ += deltaDistZ;
				hitAxis = 2;
			}

			if(mundo.eBlocoSolido(mapaX, mapaY, mapaZ)) {
				if(!camera.itemMao.equals("AR")) {
					int blocoX = mapaX + (hitAxis == 0 ? -passoX : 0);
					int blocoY = mapaY + (hitAxis == 1 ? -passoY : 0);
					int blocoZ = mapaZ + (hitAxis == 2 ? -passoZ : 0);
					mundo.colocarBloco(blocoX, blocoY, blocoZ, camera);
				} else {
					mundo.destruirBloco(mapaX, mapaY, mapaZ, camera);
				}
				return;
			}
		}
	}
	
	public void moverFrente() {
		mover(camera.foco[0], camera.foco[2]);
	}

	public void moverTras() {
		mover(-camera.foco[0], -camera.foco[2]);
	}

	public void moverDireita() {
		mover(camera.foco[2], camera.foco[0]);
	}

	public void moverEsquerda() {
		mover(camera.foco[2], -camera.foco[0]);
	}

	public void mover(float dirX, float dirZ) {
		float magSq = dirX * dirX + dirZ * dirZ;
		if(magSq <= 0.0001f) return;

		float invMag = (float) (1.0 / Math.sqrt(magSq));
		dirX *= invMag;
		dirZ *= invMag;

		float velocidade = camera.velocidade;
		float[] pos = camera.posicao;
		float halfSize = camera.hitbox[1] * 0.5f;
		
		float nx = pos[0] + dirX * velocidade;
		float nz = pos[2] + dirZ * velocidade;
		
		int by = (int) Math.floor(pos[1] - 1);
		
		float[] corners = {
			nx - halfSize, nz - halfSize,
			nx - halfSize, nz + halfSize,
			nx + halfSize, nz - halfSize,
			nx + halfSize, nz + halfSize
		};

		for(int i = 0; i < 8; i += 2) {
			int bx = (int) Math.floor(corners[i]);
			int bz = (int) Math.floor(corners[i + 1]);

			if(mundo.eBlocoSolido(bx, by, bz)) {
				return;
			}
		}
		pos[0] = nx;
		pos[2] = nz;
	}
	
	public void pular() {
		if(camera.noAr == false || gravidade==false || camera.peso==0) {
			for(int i=0; i<500; i++) {
				camera.pesoTotal = 0f;
				
				camera.posicao[1] += camera.salto;
				float[] posAjustada = mundo.verificarColisao(camera, camera.posicao[0], camera.posicao[1], camera.posicao[2]);
				camera.posicao[1] = posAjustada[1];
			}
			camera.noAr = true;
		}
	}

    public void atualizarViewMatriz() {
        Matrix.setLookAtM(viewMatriz, 0,
                          camera.posicao[0], camera.posicao[1], camera.posicao[2],
                          camera.posicao[0] + camera.foco[0],
                          camera.posicao[1] + camera.foco[1],
                          camera.posicao[2] + camera.foco[2],
                          camera.up[0], camera.up[1], camera.up[2]);
    }
	
	public void svMundo(Mundo mundo) {
		try {
			File pasta = new File(Environment.getExternalStorageDirectory(), "MiniMine");
			if(!pasta.exists()) pasta.mkdirs();

			File arquivo = new File(pasta, mundo.nome+".mini");
			FileOutputStream fos = new FileOutputStream(arquivo);
			salvarMundo(fos, mundo.seed, mundo.chunksModificados);
			fos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void crMundo(Mundo mundo) {
		try {
			File arquivo = new File(Environment.getExternalStorageDirectory() + "/MiniMine/"+mundo.nome+".mini");
			if(!arquivo.exists()) {
				pronto = true;
				return;
			}

			FileInputStream fis = new FileInputStream(arquivo);
			carregarMundo(fis, mundo);
			fis.close();
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

	public void salvarMundo(OutputStream out, int seed, Map<String, Bloco[][][]> chunksCarregados) throws IOException {
		DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(out));

		// seed
		dos.writeInt(seed);

		// quantos chunks salvos
		dos.writeInt(chunksCarregados.size());

		for(Map.Entry<String, Bloco[][][]> entry : chunksCarregados.entrySet()) {
			String chave = entry.getKey(); // chave da chunk
			Bloco[][][] chunk = entry.getValue();
			int cx = chunk.length;
			int cy = chunk[0].length;
			int cz = chunk[0][0].length;

			// salva a chave da chunk
			dos.writeUTF(chave);

			// escreve o tamanho da chunk
			dos.writeInt(cx);
			dos.writeInt(cy);
			dos.writeInt(cz);

			// conta quantos bloxos tem na chunk
			int totalNaoAr = 0;
			for(int x = 0; x < cx; x++) {
				for(int y = 0; y < cy; y++) {
					for(int z = 0; z < cz; z++) {
						Bloco b = chunk[x][y][z];
						if(b != null && !b.id.equals("AR")) {
							totalNaoAr++;
						}
					}
				}
			}
			dos.writeInt(totalNaoAr);

			// salva os dados dos blocos
			for(int x = 0; x < cx; x++) {
				for(int y = 0; y < cy; y++) {
					for(int z = 0; z < cz; z++) {
						Bloco b = chunk[x][y][z];
						if(b != null && !b.id.equals("AR")) {
							dos.writeInt(x);
							dos.writeInt(y);
							dos.writeInt(z);
							dos.writeUTF(b.id);
						}
					}
				}
			}
		}
		dos.writeFloat(camera.posicao[0]);
		dos.writeFloat(camera.posicao[1]);
		dos.writeFloat(camera.posicao[2]);
		
		dos.writeFloat(tempo);
		
		dos.flush();
		dos.close();
	}

	public void carregarMundo(InputStream in, Mundo mundo) throws IOException {
		DataInputStream dis = new DataInputStream(new BufferedInputStream(in));
		// bota a seed
		mundo.seed = dis.readInt();
		// quantidade ds chumks
		int totalChunks = dis.readInt();

		for(int i = 0; i < totalChunks; i++) {
			String chave = dis.readUTF();
			int cx = dis.readInt();
			int cy = dis.readInt();
			int cz = dis.readInt();
			Bloco[][][] chunk = new Bloco[cx][cy][cz];

			// eztraindo a chave da xhunk
			String[] partesChave = chave.split(",");
			int chunkX = Integer.parseInt(partesChave[0]);
			int chunkZ = Integer.parseInt(partesChave[1]);

			// constroi a chunk
			for(int x = 0; x < cx; x++) {
				for(int y = 0; y < cy; y++) {
					for(int z = 0; z < cz; z++) {
						int globalX = chunkX * mundo.CHUNK_TAMANHO + x;
						int globalZ = chunkZ * mundo.CHUNK_TAMANHO + z;
						chunk[x][y][z] = new Bloco(globalX, y, globalZ, "AR");
					}
				}
			}
			int totalNaoAr = dis.readInt();
			for(int k = 0; k < totalNaoAr; k++) {
				int x = dis.readInt();
				int y = dis.readInt();
				int z = dis.readInt();
				String id = dis.readUTF();

				// convertendo pra coordenadas globais
				int globalX = chunkX * mundo.CHUNK_TAMANHO + x;
				int globalZ = chunkZ * mundo.CHUNK_TAMANHO + z;
				Bloco b = new Bloco(globalX, y, globalZ, id);
				chunk[x][y][z] = b;
			}
			mundo.chunksModificados.put(chave, chunk);
			mundo.chunksCarregados.put(chave, chunk);
		}
		camera.posicao[0] = dis.readFloat();
		camera.posicao[1] = dis.readFloat();
		camera.posicao[2] = dis.readFloat();
		
		tempo = dis.readFloat();
		
		dis.close();
	}
}

class VBOGrupo {
    public int texturaId;
    public int vboId;
	public int iboId;
    public int vertices;

    public VBOGrupo(int texturaId, int vboId, int iboId, int vertices) {
        this.texturaId = texturaId;
        this.vboId = vboId;
		this.iboId = iboId;
        this.vertices = vertices;
    }
}
