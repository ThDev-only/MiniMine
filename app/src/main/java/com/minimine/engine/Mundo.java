package com.minimine.engine;

import android.opengl.GLSurfaceView;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.nio.ByteBuffer;
import android.opengl.GLES30;
import java.nio.ByteOrder;

public class Mundo {
	public GLSurfaceView tela;

    public int CHUNK_TAMANHO = 16; // padrao: 16, testes: 8
    public int MUNDO_LATERAL = 120; // padrao: 60, testes: 16
    public int RAIO_CARREGAMENTO = 2; // padrao: 3, testes: 2, inicial: 15
	public float ESCALA_2D = 0.05f;
	public float ESCALA_3D = 0.1f;
	public float CAVERNA_LIMITE = 0f;

    public final int FACES_POR_BLOCO = 6;

	public int atlasTexturaId = -1;
	public Map<String, float[]> atlasUVMapa = new HashMap<>();

	public Map<String, Bloco[][][]> chunksAtivos = new ConcurrentHashMap<>();
	public Map<String, List<VBOGrupo>> chunkVBOs = new ConcurrentHashMap<>();
	public Map<String, Boolean> chunksAlterados = new HashMap<>();
	public Map<String, Bloco[][][]> chunksModificados = new HashMap<>();
    public Map<String, Bloco[][][]> chunksCarregados = new HashMap<>();
	public String nome = "novo mundo", tipo = "plano";
	public int seed;
	public String pacoteTex;
	
	public List<String> estruturas = new ArrayList<>();

	public final float[][] NORMAIS = {
		{0f, 0f, 1f},
		{0f, 0f, -1f},
		{0f, 1f, 0f},
		{0f, -1f, 0f},
		{-1f, 0f, 0f},
		{1f, 0f, 0f}
	};
	
	public final int[] DX = {0, 0, 0, 0, -1, 1};
	public final int[] DY = {0, 0, 1, -1, 0, 0};
	public final int[] DZ = {1, -1, 0, 0, 0, 0};
	
	public final Map<String, float[]> uvCache = new HashMap<String, float[]>(64);
	
	public final float[] UV_PADRAO = {0f, 0f, 1f, 1f};

	public Mundo(GLSurfaceView tela, int seed, String nome, String tipo, String pacoteTex) {
		this.tela = tela;
		this.seed = seed;
		this.nome = nome;
	    this.tipo = tipo;
		this.pacoteTex = pacoteTex;
		this.definirEstruturas();
	}
	
	public Bloco[][][] gerarChunk(final int chunkX, final int chunkZ) {
		final Bloco[][][] chunk = new Bloco[CHUNK_TAMANHO][MUNDO_LATERAL][CHUNK_TAMANHO];
		int baseX = chunkX * CHUNK_TAMANHO;
		int baseZ = chunkZ * CHUNK_TAMANHO;
		boolean plano = tipo.equals("plano");

		int[][] alturas = new int[CHUNK_TAMANHO][CHUNK_TAMANHO];
		for(int x = 0; x < CHUNK_TAMANHO; x++) {
			for(int z = 0; z < CHUNK_TAMANHO; z++) {
				int globalX = baseX + x;
				int globalZ = baseZ + z;
				float noise2D = plano
					? 0.001f
					: (PerlinNoise2D.ruido(globalX * ESCALA_2D, globalZ * ESCALA_2D, seed) + 1f) * 0.5f;
				alturas[x][z] = (int) (noise2D * 16f + 32f);
			}
		}

		for(int x = 0; x < CHUNK_TAMANHO; x++) {
			for(int z = 0; z < CHUNK_TAMANHO; z++) {
				int globalX = baseX + x;
				int globalZ = baseZ + z;
				int altura = alturas[x][z];

				for(int y = 0; y < MUNDO_LATERAL; y++) {
					String tipoBloco;
					if(y == 0) {
						tipoBloco = "BEDROCK";
					} else {
						float noise3D = PerlinNoise3D.ruido(
							globalX * ESCALA_3D,
							y * ESCALA_3D,
							globalZ * ESCALA_3D,
							seed + 100
						);
						if(noise3D > 0.15f && y < altura) {
							tipoBloco = "AR";
						} else if(y < altura - 1) {
							tipoBloco = "PEDRA";
						} else if(y < altura) {
							tipoBloco = "TERRA";
						} else if(y == altura) {
							tipoBloco = "GRAMA";
						} else {
							tipoBloco = "AR";
						}
					}
					chunk[x][y][z] = new Bloco(globalX, y, globalZ, tipoBloco);
				}
			}
		}
		for(int x = 0; x < CHUNK_TAMANHO; x++) {
			for(int z = 0; z < CHUNK_TAMANHO; z++) {
				int globalX = baseX + x;
				int globalZ = baseZ + z;
				int altura = alturas[x][z];

				if(spawnEstrutura(0.1f, globalX, globalZ, seed)) {
					adicionarEstrutura(globalX, altura, globalZ, estruturas.get(0), chunk);
				}
				if(spawnEstrutura(0.01f, globalX, globalZ, seed)) {
					adicionarEstrutura(globalX, altura, globalZ, estruturas.get(1), chunk);
				}
				if(spawnEstrutura(0.009f, globalX, globalZ, seed)) {
					adicionarEstrutura(globalX, altura, globalZ, estruturas.get(2), chunk);
				}
			}
		}
		return chunk;
	}

	public Map<Integer, List<float[]>> calculoVBO(Bloco[][][] chunk) {
		Map<Integer, List<float[]>> dadosPorTextura = new HashMap<Integer, List<float[]>>(8);

		for(int x = 0; x < CHUNK_TAMANHO; x++) {
			for(int y = 0; y < MUNDO_LATERAL; y++) {
				for(int z = 0; z < CHUNK_TAMANHO; z++) {
					Bloco bloco = chunk[x][y][z];
					if(bloco == null || "0".equals(bloco.tipo[0])) continue;

					float[] vertices = bloco.obterVertices();
					for(int face = 0; face < 6; face++) {
						if(!faceVisivel(bloco.x, bloco.y, bloco.z, face)) continue;

						float[] dadosFace = new float[48];
						float[] normal = NORMAIS[face];
						int antes = face * 18;

						// Copia vértices + normais
						for(int v = 0; v < 6; v++) {
							int src = antes + v * 3;
							int dst = v * 8;
							System.arraycopy(vertices, src, dadosFace, dst, 3);
							System.arraycopy(normal, 0, dadosFace, dst + 3, 3);
						}

						// Resolve UVs via cache
						String recurso = bloco.tipo[face];
						float[] uv = uvCache.get(recurso);
						if(uv == null) {
							uv = atlasUVMapa.get(recurso);
							if(uv == null) uv = UV_PADRAO;
							uvCache.put(recurso, uv);
						}
						float u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];

						dadosFace[6]  = u1; dadosFace[7]  = v2;
						dadosFace[14] = u2; dadosFace[15] = v2;
						dadosFace[22] = u2; dadosFace[23] = v1;
						dadosFace[30] = u1; dadosFace[31] = v2;
						dadosFace[38] = u2; dadosFace[39] = v1;
						dadosFace[46] = u1; dadosFace[47] = v1;

						int texId = atlasTexturaId;
						List<float[]> lista = dadosPorTextura.get(texId);
						if(lista == null) {
							lista = new ArrayList<float[]>(256);
							dadosPorTextura.put(texId, lista);
						}
						lista.add(dadosFace);
					}
				}
			}
		}
		return dadosPorTextura;
	}

	public boolean faceVisivel(int x, int y, int z, int face) {
		int ny = y + DY[face];

		// Verificação vertical precoce
		if(ny < 0 || ny >= MUNDO_LATERAL) return true;

		int nx = x + DX[face];
		int nz = z + DZ[face];

		// Cálculo de chunk otimizado
		int chunkX = (nx >= 0) ? nx / CHUNK_TAMANHO : (nx + 1) / CHUNK_TAMANHO - 1;
		int chunkZ = (nz >= 0) ? nz / CHUNK_TAMANHO : (nz + 1) / CHUNK_TAMANHO - 1;

		// Formação de chave eficiente
		String chaveChunk = String.valueOf(chunkX).concat(",").concat(String.valueOf(chunkZ));

		// Busca direta no mapa
		Bloco[][][] chunkVizinho = chunksAtivos.get(chaveChunk);
		if(chunkVizinho == null) return true;

		// Cálculo de coordenadas locais
		int localX = nx - chunkX * CHUNK_TAMANHO;
		if(localX < 0) localX += CHUNK_TAMANHO;

		int localZ = nz - chunkZ * CHUNK_TAMANHO;
		if(localZ < 0) localZ += CHUNK_TAMANHO;

		// Acesso seguro
		if(localX >= CHUNK_TAMANHO || localZ >= CHUNK_TAMANHO) return true;

		Bloco vizinho = chunkVizinho[localX][ny][localZ];
		return vizinho == null || "0".equals(vizinho.tipo[0]) || !vizinho.solido;
	}
	
	// gera ou carrega chunks ja existentes
    public Bloco[][][] carregarChunk(int chunkX, int chunkY) {
        final String chave = chunkX + "," + chunkY;
        if(chunksCarregados.containsKey(chave)) {
            return chunksCarregados.get(chave);
        }
        else {
            final Bloco[][][] chunk = gerarChunk(chunkX, chunkY);
            chunksCarregados.put(chave, chunk);
            return chunk;
        }
    }

	public void adicionarEstrutura(int x, int y, int z, String json, Bloco[][][] chunk) {
		String jsonString = json;

		if(!json.equals("")) jsonString = json;
		try {
			JSONObject estrutura = new JSONObject(jsonString);

			JSONArray blocos = estrutura.getJSONArray("blocos");

			for(int i = 0; i < blocos.length(); i++) {
				JSONObject bloco = blocos.getJSONObject(i);

				int bx = bloco.getInt("x") + x;
				int by = bloco.getInt("y") + y;
				int bz = bloco.getInt("z") + z;

				addBloco(bx, by, bz, bloco.getString("tipo"), chunk);
			}
		} catch(JSONException e) {
			System.out.println("erro ao carregar o json estrutura: "+e);
		}
	}

	public boolean spawnEstrutura(float chanceSpawn, int x, int z, int seed ) {
		float TAMANHO_RUIDO = 0.99f;

		float LIMITE = 1.5f;

		float ruido = (PerlinNoise2D.ruido(x * TAMANHO_RUIDO, z * TAMANHO_RUIDO, seed) + 1f) * 0.5f;
		float normalizado = (ruido + 2f) / 4f;

		if(normalizado > LIMITE && Math.random() < chanceSpawn) {
			return true;
		}
		return false;
	}

	public void definirEstruturas() {
		String arvore1 = 
		    "{ "+
			"\"nome\": \"arvore\","+
			"\"blocos\": ["+
			"{\"x\":0, \"y\":0, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":1, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":2, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":3, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":4, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":-1, \"y\":4, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":1, \"y\":4, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":1, \"y\":4, \"z\":1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":4, \"z\":1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":-1, \"y\":4, \"z\":-1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":4, \"z\":-1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":-2, \"y\":4, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":2, \"y\":4, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":-1, \"y\":5, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":1, \"y\":5, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":5, \"z\":1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":5, \"z\":-1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":5, \"z\":0, \"tipo\": \"FOLHAS\"}"+
			"]"+
			"}";

		String arvore2 = 
		    "{ "+
			"\"nome\": \"arvore\","+
			"\"blocos\": ["+
			"{\"x\":0, \"y\":0, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":1, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":2, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":3, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":4, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":0, \"y\":5, \"z\":0, \"tipo\": \"TRONCO_CARVALHO\"},"+
			"{\"x\":-1, \"y\":5, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":1, \"y\":5, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":1, \"y\":5, \"z\":1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":5, \"z\":1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":-1, \"y\":5, \"z\":-1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":5, \"z\":-1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":-2, \"y\":5, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":2, \"y\":5, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":-1, \"y\":6, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":1, \"y\":6, \"z\":0, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":6, \"z\":1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":6, \"z\":-1, \"tipo\": \"FOLHAS\"},"+
			"{\"x\":0, \"y\":6, \"z\":0, \"tipo\": \"FOLHAS\"}"+
			"]"+
			"}";

		String pedra1 =
			"{"+
			"\"blocos\": ["+
			"{\"x\": 0, \"y\": 1, \"z\": 0, \"tipo\": \"PEDRA\"},"+
			"{\"x\": 1, \"y\": 1, \"z\": 0, \"tipo\": \"PEDREGULHO\"},"+
			"{\"x\": 1, \"y\": 1, \"z\": 1, \"tipo\": \"PEDRA\"},"+
			"{\"x\": -1, \"y\": 1, \"z\": 0, \"tipo\": \"PEDREGULHO\"},"+
			"{\"x\": 1, \"y\": 1, \"z\": 1, \"tipo\": \"PEDREGULHO\"},"+
			"{\"x\": 0, \"y\": 1, \"z\": 1, \"tipo\": \"PEDREGULHO\"},"+
			"{\"x\": 0, \"y\": 2, \"z\": 0, \"tipo\": \"PEDRA\"},"+
			"{\"x\": 0, \"y\": 2, \"z\": 1, \"tipo\": \"PEDREGULHO\"}"+
			"]"+
			"}";
		estruturas.add(arvore1);
		estruturas.add(arvore2);
		estruturas.add(pedra1);
	}

	public void destruirBloco(final float globalX, final float y, final float globalZ, final Player player) {
		final int chunkX = (int) Math.floor(globalX / (float) CHUNK_TAMANHO);
		final int chunkZ = (int) Math.floor(globalZ / (float) CHUNK_TAMANHO);
		final String chaveChunk = chunkX + "," + chunkZ;

		final Bloco[][][] chunk = carregarChunk(chunkX, chunkZ);

		final int intY = (int) y;
		final int localX = (int) (globalX - (chunkX * CHUNK_TAMANHO));
		final int localZ = (int) (globalZ - (chunkZ * CHUNK_TAMANHO));

		if(y < 0 || y >= MUNDO_LATERAL || localX < 0 || localX >= CHUNK_TAMANHO || localZ < 0 || localZ >= CHUNK_TAMANHO) return;

		final Bloco blocoExistente = chunk[localX][intY][localZ];

		if(blocoExistente == null || blocoExistente.tipo[0].equals("0")) return;

		player.inventario.get(0).tipo = blocoExistente.id;
		player.inventario.get(0).quant += 1;
		chunk[localX][intY][localZ] = null;

		if(chunksAtivos.containsKey(chaveChunk)) {
			chunksAlterados.put(chaveChunk, true);
			chunksModificados.put(chaveChunk, chunk);
		}
	}

	public void colocarBloco(final float globalX, final float y, final float globalZ,  final Player player) {
		int chunkX = (int) Math.floor(globalX / (float) CHUNK_TAMANHO);
		int chunkZ = (int) Math.floor(globalZ / (float) CHUNK_TAMANHO);
		final String chaveChunk = chunkX + "," + chunkZ;

		// carrega ou gera o chunk correspondente
		Bloco[][][] chunk = carregarChunk(chunkX, chunkZ);

		int intY = (int) y;
		int localX = (int) (globalX - (chunkX * CHUNK_TAMANHO));
		int localZ = (int) (globalZ - (chunkZ * CHUNK_TAMANHO));

		if(y < 0 || y >= MUNDO_LATERAL || localX < 0 || localX >= CHUNK_TAMANHO || localZ < 0 || localZ >= CHUNK_TAMANHO) return;

		Bloco blocoExistente = chunk[localX][intY][localZ];
		if(blocoExistente != null && !blocoExistente.tipo[0].equals("0")) return;

		// define o bloco
		if(player.inventario.get(0).quant >= 0)chunk[localX][intY][localZ] = new Bloco((int) globalX, (int) y, (int) globalZ, player.itemMao);

		// se o chunk estiver ativo marca como alterado para atualizacao da VBO
		if(chunksAtivos.containsKey(chaveChunk)) {
			chunksAlterados.put(chaveChunk, true);
			chunksModificados.put(chaveChunk, chunk);
		}
	}

	public void addBloco(final float globalX, final float y, final float globalZ,  final String tipo, final Bloco[][][] chunk) {
		int chunkX = (int) Math.floor(globalX / (float) CHUNK_TAMANHO);
		int chunkZ = (int) Math.floor(globalZ / (float) CHUNK_TAMANHO);

		int intY = (int) y;
		int localX = (int) (globalX - (chunkX * CHUNK_TAMANHO));
		int localZ = (int) (globalZ - (chunkZ * CHUNK_TAMANHO));

		if(y < 0 || y >= MUNDO_LATERAL || localX < 0 || localX >= CHUNK_TAMANHO || localZ < 0 || localZ >= CHUNK_TAMANHO) {
			return;
		}

		Bloco blocoExistente = chunk[localX][intY][localZ];
		if(blocoExistente != null && !blocoExistente.tipo[0].equals("0")) {
			return;
		}

		chunk[localX][intY][localZ] = new Bloco((int) globalX, (int) y, (int) globalZ, tipo);
	}

	public boolean noChao(Camera camera) {
		float posPes = camera.posicao[1] - 1 - (camera.hitbox[0] / 2f);

		float yTeste = posPes - 0.1f;
		int by = (int) Math.floor(yTeste);

		float halfLargura = camera.hitbox[1] / 2f;
		int bx1 = (int) Math.floor(camera.posicao[0] - halfLargura);
		int bx2 = (int) Math.floor(camera.posicao[0] + halfLargura);
		int bz1 = (int) Math.floor(camera.posicao[2] - halfLargura);
		int bz2 = (int) Math.floor(camera.posicao[2] + halfLargura);

		for (int bx = bx1; bx <= bx2; bx++) {
			for (int bz = bz1; bz <= bz2; bz++) {
				if (eBlocoSolido(bx, by, bz)) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean eBlocoSolido(int bx, int by, int bz) {
		if (by < 0 || by >= MUNDO_LATERAL) return false;
		int chunkX = (int) Math.floor(bx / (float) CHUNK_TAMANHO);
		int chunkZ = (int) Math.floor(bz / (float) CHUNK_TAMANHO);
		Bloco[][][] chunk = chunksAtivos.get(chunkX + "," + chunkZ);
		if (chunk == null) return false;
		int localX = bx - chunkX * CHUNK_TAMANHO;
		int localZ = bz - chunkZ * CHUNK_TAMANHO;
		if (localX < 0 || localX >= CHUNK_TAMANHO || localZ < 0 || localZ >= CHUNK_TAMANHO) return false;
		Bloco bloco = chunk[localX][by][localZ];
		return bloco != null && bloco.solido;
	}

	public float[] verificarColisao(Camera camera, float novoTx, float novoTy, float novoTz) {
		float altura = camera.hitbox[0];
		float largura = camera.hitbox[1];
		float metadeLargura = largura / 2f;
		float novoX = novoTx;
		float novoY = novoTy - 1.5f;
		float novoZ = novoTz;
		float EPSILON = 0.0001f;

		for (int iter = 0; iter < 5; iter++) {
			float minX = novoX - metadeLargura;
			float maxX = novoX + metadeLargura;
			float minY = novoY;
			float maxY = novoY + altura;
			float minZ = novoZ - metadeLargura;
			float maxZ = novoZ + metadeLargura;

			int startX = (int) Math.floor(minX);
			int endX   = (int) Math.floor(maxX);
			int startY = (int) Math.floor(minY);
			int endY   = (int) Math.floor(maxY);
			int startZ = (int) Math.floor(minZ);
			int endZ   = (int) Math.floor(maxZ);

			boolean colidiu = false;
			boolean ajustouEixo = false;

			for (int bx = startX; bx <= endX; bx++) {
				for (int by = startY; by <= endY; by++) {
					for (int bz = startZ; bz <= endZ; bz++) {
						if (!eBlocoSolido(bx, by, bz)) continue;

						float blocoMinX = bx;
						float blocoMaxX = bx + 1f;
						float blocoMinY = by;
						float blocoMaxY = by + 1f;
						float blocoMinZ = bz;
						float blocoMaxZ = bz + 1f;

						float sobreposX = Math.max(0f, Math.min(maxX, blocoMaxX) - Math.max(minX, blocoMinX));
						float sobreposY = Math.max(0f, Math.min(maxY, blocoMaxY) - Math.max(minY, blocoMinY));
						float sobreposZ = Math.max(0f, Math.min(maxZ, blocoMaxZ) - Math.max(minZ, blocoMinZ));

						if (sobreposX > EPSILON && sobreposX < sobreposY && sobreposX < sobreposZ) {
							novoX += (novoX < bx + 0.5f) ? -sobreposX : sobreposX;
							ajustouEixo = true;
						} else if (sobreposY > EPSILON && sobreposY < sobreposX && sobreposY < sobreposZ) {
							novoY += (novoY < by + 0.5f) ? -sobreposY : sobreposY;
							ajustouEixo = true;
						} else if (sobreposZ > EPSILON) {
							novoZ += (novoZ < bz + 0.5f) ? -sobreposZ : sobreposZ;
							ajustouEixo = true;
						}

						colidiu = true;
					}
				}
			}

			if (!colidiu || !ajustouEixo) break;
		}

		return new float[] { novoX, novoY + 1.5f, novoZ };
	}
}
