package me.cortex.voxy.commonImpl.importers;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.ByteBufferBackedInputStream;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.io.IOUtils;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.zstd.Zstd;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class DHImporter implements IDataImporter {
    private final Connection db;
    private final WorldEngine engine;
    private final Service service;
    private final Level world;
    private final int bottomOfWorld;
    private final int worldHeightSections;
    // MC 1.21.1: Registry → HolderLookup.RegistryLookup, Holder.Reference → Holder
    private final Holder<Biome> defaultBiome;
    private final HolderLookup.RegistryLookup<Biome> biomeRegistry;
    private final HolderLookup.RegistryLookup<Block> blockRegistry;
    private Thread runner;
    private volatile boolean isRunning = false;
    private final AtomicInteger processedChunks = new AtomicInteger();
    private int totalChunks;
    private IUpdateCallback updateCallback;

    private record Task(int x, int z, int fmt, int compression) {
        public long distanceFromZero() {
            return ((long)this.x)*this.x+((long)this.z)*this.z;
        }
    }
    private final ConcurrentLinkedDeque<Task> tasks = new ConcurrentLinkedDeque<>();
    private static final class WorkCTX {
        private final PreparedStatement stmt;
        private final long[] storageCache;
        private final byte[] colScratch;
        private final VoxelizedSection section;

        private ByteBuffer zstdScratch;
        private ByteBuffer zstdScratch2;
        private final long zstdDCtx;

        public WorkCTX(PreparedStatement stmt, int worldHeight) {
            this.stmt = stmt;
            this.storageCache = new long[64*16*worldHeight];
            this.colScratch = new byte[1<<16];
            this.section = VoxelizedSection.createEmpty();
            this.zstdDCtx = Zstd.ZSTD_createDCtx();
        }

        public void free() {
            if (this.zstdScratch != null) {
                MemoryUtil.memFree(this.zstdScratch);
                MemoryUtil.memFree(this.zstdScratch2);
                Zstd.ZSTD_freeDCtx(this.zstdDCtx);
            }
        }
    }

    public DHImporter(File file, WorldEngine worldEngine, Level mcWorld, ServiceManager servicePool, BooleanSupplier rateLimiter) {
        this.engine = worldEngine;
        this.world = mcWorld;
        this.biomeRegistry = mcWorld.registryAccess().lookupOrThrow(Registries.BIOME);
        this.defaultBiome = this.biomeRegistry.getOrThrow(Biomes.PLAINS);
        this.blockRegistry = mcWorld.registryAccess().lookupOrThrow(Registries.BLOCK);

        // MC 1.21.1: Level.getMinY() → getMinBuildHeight()
        this.bottomOfWorld = mcWorld.getMinBuildHeight();
        int worldHeight = mcWorld.getHeight();
        this.worldHeightSections = (worldHeight+15)/16;

        String con = "jdbc:sqlite:" + file.getPath();
        try {
            this.db = DriverManager.getConnection(con);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        this.service = servicePool.createService(()->{
            try {
                var dataFetchStmt = this.db.prepareStatement("SELECT Data,ColumnGenerationStep,Mapping FROM FullData WHERE DetailLevel = 0 AND PosX = ? AND PosZ = ?;");
                PreparedStatement v2FetchStmtTmp = null;
                try {
                    v2FetchStmtTmp = this.db.prepareStatement("SELECT Data,Mapping,NorthAdjData,SouthAdjData,EastAdjData,WestAdjData FROM FullData WHERE DetailLevel = 0 AND PosX = ? AND PosZ = ?;");
                } catch (SQLException e) {
                    //databases from before DH added the adjacent data columns only contain format 1 rows
                }
                final var v2FetchStmt = v2FetchStmtTmp;
                var ctx = new WorkCTX(dataFetchStmt, this.worldHeightSections*16);
                return new Pair<>(()->{
                    var task = this.tasks.poll();
                    if (task == null) {
                        return;
                    }
                    if (task.fmt == 2) {
                        this.importSectionV2(v2FetchStmt, ctx, task);
                    } else {
                        this.importSection(dataFetchStmt, ctx, task);
                    }
                },()->{
                    ctx.free();
                    try {
                        dataFetchStmt.close();
                        if (v2FetchStmt != null) {
                            v2FetchStmt.close();
                        }
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, 10, "DH Importer", rateLimiter);
    }

    public void runImport(IUpdateCallback updateCallback, ICompletionCallback completionCallback) {
        if (this.isRunning()) {
            throw new IllegalStateException();
        }
        this.engine.acquireRef();
        this.updateCallback = updateCallback;
        this.runner = new Thread(()-> {
            Queue<Task> taskQ = new PriorityQueue<>(Comparator.comparingLong(Task::distanceFromZero));
            try (var stmt = this.db.createStatement()) {
                var resSet = stmt.executeQuery("SELECT PosX,PosZ,CompressionMode,DataFormatVersion FROM FullData WHERE DetailLevel = 0;");
                while (resSet.next()) {
                    int x = resSet.getInt(1);
                    int z = resSet.getInt(2);
                    int compression = resSet.getInt(3);
                    int format = resSet.getInt(4);
                    if (format != 1 && format != 2) {
                        Logger.warn("Unknown format mode: " + format);
                        continue;
                    }
                    if (compression != 4) {
                        //mode 3 is LZMA2, which needs org.tukaani.xz. That library is not bundled
                        // (it collides with Distant Horizons' own copy under JPMS), so LZMA-compressed
                        // sections are skipped. Modern DH defaults to zstd (mode 4) so this is rare.
                        Logger.warn("Unsupported DH compression mode (only zstd/4 supported): " + compression);
                        continue;
                    }
                    taskQ.add(new Task(x, z, format, compression));
                }
                resSet.close();

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            this.totalChunks = taskQ.size() * (4*4);//(since there are 4*4 chunks to every dh section)

            while (this.isRunning&&!taskQ.isEmpty()) {
                this.tasks.add(taskQ.poll());
                this.service.execute();

                while (this.tasks.size() > 100 && this.isRunning) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            while (!this.tasks.isEmpty()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            completionCallback.onCompletion(this.processedChunks.get());
            this.shutdown();
        });
        this.isRunning = true;
        this.runner.setDaemon(true);
        this.runner.start();
    }

    private static String getSerialBlockState(BlockState state) {
        var props = new ArrayList<>(state.getProperties());
        props.sort((a, b) -> a.getName().compareTo(b.getName()));
        StringBuilder b = new StringBuilder();
        for (var prop : props) {
            String val = "NULL";
            if (state.hasProperty(prop)) {
                val = state.getValue(prop).toString();
            }
            b.append("{").append(prop.getName()).append(":").append(val).append("}");
        }
        return b.toString();
    }

    //TODO: add global mapping cache (with thread local secondary cache)
    private long[] readMappings(InputStream in, WorkCTX ctx) throws IOException {
        final String BLOCK_STATE_SEPARATOR_STRING = "_DH-BSW_";
        final String STATE_STRING_SEPARATOR = "_STATE_";
        var stream = new DataInputStream(in);
        int entries = stream.readInt();
        if (entries < 0)
            throw new IllegalStateException();
        long[] out = new long[entries];
        for (int i = 0; i < entries; i++) {
            int biomeId;
            int blockId;
            String encEntry = stream.readUTF();
            int idx = encEntry.indexOf(BLOCK_STATE_SEPARATOR_STRING);
            if (idx == -1)
                throw new IllegalStateException();
            {
                var biomeRes = ResourceLocation.parse(encEntry.substring(0, idx));
                // MC 1.21.1: RegistryLookup.get() requires ResourceKey, returns Optional<Holder.Reference<T>>
                // Explicit type needed because orElse() with Holder<Biome> default causes type mismatch
                var biomeKey = ResourceKey.create(Registries.BIOME, biomeRes);
                Holder<Biome> biome = this.biomeRegistry.get(biomeKey).map(h -> (Holder<Biome>)h).orElse(this.defaultBiome);
                biomeId = this.engine.getMapper().getIdForBiome(biome);
            }
            {
                int b = idx + BLOCK_STATE_SEPARATOR_STRING.length();
                if (encEntry.substring(b).equals("AIR")) {
                    blockId = 0;
                } else {
                    var sIdx = encEntry.indexOf(STATE_STRING_SEPARATOR, b);
                    String bStateStr = null;
                    if (sIdx != -1) {
                        bStateStr = encEntry.substring(sIdx + STATE_STRING_SEPARATOR.length());
                    }
                    var bId = ResourceLocation.parse(encEntry.substring(b, sIdx != -1 ? sIdx : encEntry.length()));
                    // MC 1.21.1: RegistryLookup.get() requires ResourceKey, returns Optional<Holder<T>>
                    var blockKey = ResourceKey.create(Registries.BLOCK, bId);
                    var maybeBlock = this.blockRegistry.get(blockKey);
                    Block block = Blocks.AIR;
                    if (maybeBlock.isPresent()) {
                        block = maybeBlock.get().value();
                    }
                    var state = block.defaultBlockState();
                    if (bStateStr != null && block != Blocks.AIR) {
                        boolean found = false;
                        for (BlockState bState : block.getStateDefinition().getPossibleStates()) {
                            if (getSerialBlockState(bState).equals(bStateStr)) {
                                state = bState;
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            Logger.warn("Could not find block state with data", encEntry.substring(b));
                        }
                    }
                    if (block  == Blocks.AIR) {
                        Logger.warn("Could not find block entry with id:", bId);
                    }
                    blockId = this.engine.getMapper().getIdForBlockState(state);
                }
            }
            out[i] = Mapper.composeMappingId((byte) 0, blockId, biomeId);
        }
        stream.close();
        return out;
    }

    private static int getId(long dp) {
        return (int)(dp&Integer.MAX_VALUE);
    }

    private static int getHeight(long dp) {
        return (int)((dp>>>32)&((1<<12)-1));
    }

    private static int getMinHeight(long dp) {
        return (int)((dp>>>(32+12))&((1<<12)-1));
    }

    private static int getSkyLight(long dp) {
        return (int)((dp>>>(32+12+12))&0xF);
    }

    private static int getBlockLight(long dp) {
        return (int)((dp>>>(32+12+12+4))&0xF);
    }

    private static InputStream createDecompressedStream(int decompressor, InputStream in, WorkCTX ctx) throws IOException {
        if (decompressor == 4) {
            if (ctx.zstdScratch == null) {
                ctx.zstdScratch = MemoryUtil.memAlloc(8196);
                ctx.zstdScratch2 = MemoryUtil.memAlloc(8196);
            }
            ctx.zstdScratch.clear();
            ctx.zstdScratch2.clear();
            //read the entire blob, growing the scratch buffer whenever it fills up
            // (IOUtils.read fills the buffer's remaining space, so a non-full buffer means EOF)
            try(var channel = Channels.newChannel(in)) {
                while (true) {
                    IOUtils.read(channel, ctx.zstdScratch);
                    if (ctx.zstdScratch.hasRemaining()) {
                        break;
                    }
                    var newBuffer = MemoryUtil.memAlloc(ctx.zstdScratch.capacity()*2);
                    ctx.zstdScratch.flip();
                    newBuffer.put(ctx.zstdScratch);
                    MemoryUtil.memFree(ctx.zstdScratch);
                    ctx.zstdScratch = newBuffer;
                }
            }
            ctx.zstdScratch.limit(ctx.zstdScratch.position()).rewind();
            {
                long decompSize = Zstd.ZSTD_getFrameContentSize(ctx.zstdScratch);
                if (decompSize < 0 || decompSize > (Integer.MAX_VALUE/2)) {
                    throw new IllegalStateException("Invalid zstd frame content size: " + decompSize);
                }
                if (ctx.zstdScratch2.capacity() < decompSize) {
                    MemoryUtil.memFree(ctx.zstdScratch2);
                    ctx.zstdScratch2 = MemoryUtil.memAlloc((int) (decompSize * 1.1));
                }
            }
            //ZSTD_decompressDCtx takes (dctx, dst, src)
            long size = Zstd.ZSTD_decompressDCtx(ctx.zstdDCtx, ctx.zstdScratch2, ctx.zstdScratch);
            if (Zstd.ZSTD_isError(size)) {
                throw new IllegalStateException("ZSTD EXCEPTION: " + Zstd.ZSTD_getErrorName(size));
            }
            ctx.zstdScratch2.position(0);
            ctx.zstdScratch2.limit((int) size);
            return new ByteBufferBackedInputStream(ctx.zstdScratch2);
        } else {
            throw new IllegalArgumentException("Unknown compressor " + decompressor);
        }
    }

    //TODO: create VoxelizedSection of 32*32*32
    private void readColumnData(int X, int Z, InputStream in, WorkCTX ctx, long[] mapping) throws IOException {
        //TODO: add datacache betweein XZ input stream
        var stream = new DataInputStream(in);
        long[] storage = ctx.storageCache;
        VoxelizedSection section = ctx.section;
        byte[] col = ctx.colScratch;
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int bPos = Integer.expand(x&0xF, 0b00_00_0000_0000_1111) |
                           Integer.expand(z, 0b00_11_0000_1111_0000);
                short cl = stream.readShort();
                if (cl < 0) {
                    throw new IllegalStateException();
                }
                stream.read(col, 0, cl*8);
                for (int j = 0; j < cl; j++) {
                    long entry = (long) LONG.get(col, j*8);
                    long mEntry = Mapper.withLight(mapping[getId(entry)], (getBlockLight(entry) << 4) | getSkyLight(entry));
                    int startY = getMinHeight(entry);
                    int tall = getHeight(entry);
                    int endY = Math.min(startY+tall, this.worldHeightSections*16);
                    //if (endY < startY+tall && ((this.worldHeightSections*16)+1 != startY+tall)) {
                    //    int a = 0;
                    //}
                    //Insert all entries into data cache
                    startY = Integer.expand(startY, 0b11111111_00_1111_0000_0000);
                    endY = Integer.expand(endY, 0b11111111_00_1111_0000_0000);
                    final int Msk = 0b11111111_00_1111_0000_0000;
                    final int iMsk1 = (~Msk)+1;
                    for (int y = startY; y != endY; y = (y+iMsk1)&Msk) {
                        storage[y+bPos] = mEntry;
                        //touched[(idx >>> 12)>>6] |= 1L<<(idx&0x3f);
                    }
                }
            }

            if ((x+1)%16==0) {
                for (int sz = 0; sz < 4; sz++) {
                    for (int sy = 0; sy < this.worldHeightSections; sy++) {
                        {
                            int base = (sz|(sy<<2))<<12;
                            int nonAirCount = 0;
                            final var dat = section.section;
                            for (int i = 0; i < 4096; i++) {
                                nonAirCount += Mapper.isAir(dat[i] = storage[i+base])?0:1;
                            }
                            section.lvl0NonAirCount = nonAirCount;
                        }

                        WorldConversionFactory.mipSection(section, this.engine.getMapper());

                        section.setPosition(X*4+(x>>4), sy+(this.bottomOfWorld>>4), (Z*4)+sz);
                        WorldUpdater.insertUpdate(this.engine, section);
                    }

                    int count = this.processedChunks.incrementAndGet();
                    this.updateCallback.onUpdate(count, this.totalChunks);
                }
                Arrays.fill(storage, 0);
                //Process batch
            }
        }
        stream.close();
    }
    private void importSection(PreparedStatement dataFetchStmt, WorkCTX ctx, Task task) {
        if (!this.isRunning) {
            return;
        }
        try {
            dataFetchStmt.setInt(1, task.x);
            dataFetchStmt.setInt(2, task.z);
            try (var rs = dataFetchStmt.executeQuery()) {
                var mapping = readMappings(createDecompressedStream(task.compression, rs.getBinaryStream(3), ctx), ctx);
                //var columnGenStep = new byte[64*64];
                //readStream(rs.getBinaryStream(2), cache, columnGenStep);
                readColumnData(task.x, task.z, createDecompressedStream(task.compression, rs.getBinaryStream(1), ctx), ctx, mapping);
            };
        } catch (SQLException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static int readVarint(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= 32) {
                throw new IOException("Invalid varint");
            }
            b = in.readByte();
            value |= (b & 127) << shift;
            shift += 7;
        } while ((b & 128) != 0);
        return value;
    }

    private static int zigzagDecode(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    //Decodes a DH format 2 blob (see DH's FullDataSourceV2DTO#readBlobToDataSourceDataArrayV2) into
    // the same datapoint longs as format 1. The blob is 5 sequential streams over the given column
    // range: varint column lengths, varint (id<<2 | lightFlag<<1 | discontinuityFlag), varint heights,
    // zigzag varint bottomY prediction errors (only for flagged datapoints), then packed light bytes
    // (only for flagged datapoints). The flags are stashed in the minY/blockLight bit fields until the
    // pass that resolves them, mirroring DH's own reader.
    private void readV2Blob(InputStream in, long[][] cols, int minX, int maxX, int minZ, int maxZ) throws IOException {
        var stream = new DataInputStream(in);
        // 1. column lengths
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                int count = readVarint(stream);
                if (count > 8192) {
                    throw new IOException("Corrupt column length: " + count);
                }
                cols[(x<<6)|z] = new long[count];
            }
        }
        // 2. ids, with the discontinuity/light flags stashed in the minY/blockLight fields
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                long[] col = cols[(x<<6)|z];
                for (int i = 0; i < col.length; i++) {
                    int enc = readVarint(stream);
                    col[i] = ((long)(enc >>> 2)) | (((long)(enc & 1)) << 44) | (((long)((enc >>> 1) & 1)) << 60);
                }
            }
        }
        // 3. heights
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                long[] col = cols[(x<<6)|z];
                for (int i = 0; i < col.length; i++) {
                    col[i] |= ((long)(readVarint(stream) & 0xFFF)) << 32;
                }
            }
        }
        // 4. bottomY, predicted as directly below the previous datapoint, only mispredictions are stored
        int previousBottomY = 0;
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                long[] col = cols[(x<<6)|z];
                for (int i = 0; i < col.length; i++) {
                    long data = col[i];
                    int error = 0;
                    if (((data >>> 44) & 1) != 0) {
                        error = zigzagDecode(readVarint(stream));
                    }
                    int bottomY = previousBottomY - ((int)((data >>> 32) & 0xFFF)) + error;
                    previousBottomY = bottomY;
                    col[i] = (data & ~(0xFFFL << 44)) | (((long)(bottomY & 0xFFF)) << 44);
                }
            }
        }
        // 5. packed light for datapoints with the light flag set
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                long[] col = cols[(x<<6)|z];
                for (int i = 0; i < col.length; i++) {
                    long data = col[i];
                    if (((data >>> 60) & 0xF) != 0) {
                        int packed = stream.readByte();
                        col[i] = (data & ~(0xFFL << 56)) | (((long)(packed & 0xF)) << 56) | (((long)((packed >>> 4) & 0xF)) << 60);
                    }
                }
            }
        }
        stream.close();
    }

    //Same storage fill + section flush as readColumnData, but sourced from pre-decoded columns
    // since format 2 stores fields column-major across the whole blob instead of streaming per column
    private void processColumns(int X, int Z, long[][] cols, WorkCTX ctx, long[] mapping) {
        long[] storage = ctx.storageCache;
        VoxelizedSection section = ctx.section;
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int bPos = Integer.expand(x&0xF, 0b00_00_0000_0000_1111) |
                           Integer.expand(z, 0b00_11_0000_1111_0000);
                long[] col = cols[(x<<6)|z];
                if (col == null) {
                    continue;
                }
                for (long entry : col) {
                    long mEntry = Mapper.withLight(mapping[getId(entry)], (getBlockLight(entry) << 4) | getSkyLight(entry));
                    int startY = getMinHeight(entry);
                    int tall = getHeight(entry);
                    int endY = Math.min(startY+tall, this.worldHeightSections*16);
                    if (endY <= startY) {
                        continue;
                    }
                    startY = Integer.expand(startY, 0b11111111_00_1111_0000_0000);
                    endY = Integer.expand(endY, 0b11111111_00_1111_0000_0000);
                    final int Msk = 0b11111111_00_1111_0000_0000;
                    final int iMsk1 = (~Msk)+1;
                    for (int y = startY; y != endY; y = (y+iMsk1)&Msk) {
                        storage[y+bPos] = mEntry;
                    }
                }
            }

            if ((x+1)%16==0) {
                for (int sz = 0; sz < 4; sz++) {
                    for (int sy = 0; sy < this.worldHeightSections; sy++) {
                        {
                            int base = (sz|(sy<<2))<<12;
                            int nonAirCount = 0;
                            final var dat = section.section;
                            for (int i = 0; i < 4096; i++) {
                                nonAirCount += Mapper.isAir(dat[i] = storage[i+base])?0:1;
                            }
                            section.lvl0NonAirCount = nonAirCount;
                        }

                        WorldConversionFactory.mipSection(section, this.engine.getMapper());

                        section.setPosition(X*4+(x>>4), sy+(this.bottomOfWorld>>4), (Z*4)+sz);
                        WorldUpdater.insertUpdate(this.engine, section);
                    }

                    int count = this.processedChunks.incrementAndGet();
                    this.updateCallback.onUpdate(count, this.totalChunks);
                }
                Arrays.fill(storage, 0);
            }
        }
    }

    private void importSectionV2(PreparedStatement dataFetchStmt, WorkCTX ctx, Task task) {
        if (!this.isRunning) {
            return;
        }
        if (dataFetchStmt == null) {
            Logger.warn("DH database is missing the adjacent data columns, cannot import format 2 section at " + task.x + ", " + task.z);
            return;
        }
        try {
            dataFetchStmt.setInt(1, task.x);
            dataFetchStmt.setInt(2, task.z);
            try (var rs = dataFetchStmt.executeQuery()) {
                var dataBlob = rs.getBinaryStream(1);
                var mappingBlob = rs.getBinaryStream(2);
                if (dataBlob == null || mappingBlob == null) {
                    return;
                }
                var mapping = readMappings(createDecompressedStream(task.compression, mappingBlob, ctx), ctx);
                var cols = new long[64*64][];
                //the main blob only contains the interior 62x62 columns, the border ring is stored
                // in the four adjacent data blobs (see DH's FullDataMinMaxPosUtil)
                readV2Blob(createDecompressedStream(task.compression, dataBlob, ctx), cols, 1, 63, 1, 63);
                var north = rs.getBinaryStream(3);
                if (north != null) {
                    readV2Blob(createDecompressedStream(task.compression, north, ctx), cols, 0, 64, 0, 1);
                }
                var south = rs.getBinaryStream(4);
                if (south != null) {
                    readV2Blob(createDecompressedStream(task.compression, south, ctx), cols, 0, 64, 63, 64);
                }
                var east = rs.getBinaryStream(5);
                if (east != null) {
                    readV2Blob(createDecompressedStream(task.compression, east, ctx), cols, 63, 64, 0, 64);
                }
                var west = rs.getBinaryStream(6);
                if (west != null) {
                    readV2Blob(createDecompressedStream(task.compression, west, ctx), cols, 0, 1, 0, 64);
                }
                this.processColumns(task.x, task.z, cols, ctx, mapping);
            }
        } catch (Exception e) {
            //don't let one corrupt section abort the entire import, but clear any partially
            // filled storage so it can't leak into the next section processed by this worker
            Arrays.fill(ctx.storageCache, 0);
            Logger.warn("Failed to import DH format 2 section at " + task.x + ", " + task.z, e);
        }
    }

    public void shutdown() {
        if (!this.isRunning) {
            return;
        }
        this.isRunning = false;
        while (!this.tasks.isEmpty())
            this.tasks.poll();
        try {
            if (this.runner != Thread.currentThread()) {
                this.runner.join();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        this.service.shutdown();
        this.engine.releaseRef();
        try {
            this.db.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        this.updateCallback = null;
        this.runner = null;
    }

    @Override
    public boolean isRunning() {
        return this.isRunning;
    }

    @Override
    public WorldEngine getEngine() {
        return this.engine;
    }

    private static VarHandle create(Class<?> viewArrayClass) {
        return MethodHandles.byteArrayViewVarHandle(viewArrayClass, ByteOrder.BIG_ENDIAN);
    }

    public static final boolean HasRequiredLibraries;

    private static final VarHandle LONG = create(long[].class);
    static {
        boolean hasJDBC = false;
        try {
            Class.forName("org.sqlite.JDBC");
            hasJDBC = true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            //throw new RuntimeException(e);
            Logger.warn("Unable to load sqlite JDBC, DHImporting wont be available", e);
        }
        HasRequiredLibraries = hasJDBC;
    }

}
