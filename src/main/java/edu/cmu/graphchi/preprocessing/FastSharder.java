package edu.cmu.graphchi.preprocessing;

import edu.cmu.graphchi.ChiFilenames;
import edu.cmu.graphchi.ChiVertex;
import edu.cmu.graphchi.LoggingInitializer;
import edu.cmu.graphchi.datablocks.BytesToValueConverter;
import edu.cmu.graphchi.shards.MemoryShard;
import edu.cmu.graphchi.shards.SlidingShard;
import nom.tam.util.BufferedDataInputStream;

import java.io.*;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Logger;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * New version of sharder that requires predefined number of shards
 * and translates the vertex ids in order to randomize the order.
 * Need to use VertexIdTranslate.  Requires enough memory to store vertex degrees (TODO, fix).
 */
public class FastSharder <EdgeValueType> {

    private String baseFilename;
    private int numShards;
    private int initialIntervalLength;
    private VertexIdTranslate preIdTranslate;
    private VertexIdTranslate finalIdTranslate;

    private DataOutputStream[] shovelStreams;
    private int maxVertexId = 0;

    private int[] inDegrees;
    private int[] outDegrees;
    private boolean memoryEfficientDegreeCount = false;
    private long numEdges = 0;

    private BytesToValueConverter<EdgeValueType> edgeValueTypeBytesToValueConverter;

    private EdgeProcessor<EdgeValueType> edgeProcessor;

    private static final Logger logger = LoggingInitializer.getLogger("fast-sharder");



    public FastSharder(String baseFilename, int numShards,
                       EdgeProcessor<EdgeValueType> edgeProcessor, BytesToValueConverter<EdgeValueType> edgeValConverter) throws IOException {
        this.baseFilename = baseFilename;
        this.numShards = numShards;
        this.initialIntervalLength = Integer.MAX_VALUE / numShards;
        this.preIdTranslate = new VertexIdTranslate(this.initialIntervalLength, numShards);
        this.edgeProcessor = edgeProcessor;
        this.edgeValueTypeBytesToValueConverter = edgeValConverter;

        shovelStreams = new DataOutputStream[numShards];
        for(int i=0; i < numShards; i++) {
            shovelStreams[i] = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(shovelFilename(i))));
        }
        valueTemplate =  new byte[edgeValueTypeBytesToValueConverter.sizeOf()];
    }

    private String shovelFilename(int i) {
        return baseFilename + ".shovel." + i;
    }


    public void addEdge(int from, int to, String edgeValueToken) throws IOException {
        if (from == to) {
            edgeProcessor.receiveVertexValue(from, edgeValueToken);
            return;
        }
        int preTranslatedIdFrom = preIdTranslate.forward(from);
        int preTranslatedTo = preIdTranslate.forward(to);

        if (maxVertexId < from) maxVertexId = from;
        if (maxVertexId < to)  maxVertexId = to;

        addToShovel(to % numShards, preTranslatedIdFrom, preTranslatedTo, edgeProcessor.receiveEdge(from, to, edgeValueToken));
    }


    private byte[] valueTemplate;

    private void addToShovel(int shard, int preTranslatedIdFrom, int preTranslatedTo,
                             EdgeValueType value) throws IOException {
        DataOutputStream strm = shovelStreams[shard];
        strm.writeLong(packEdges(preTranslatedIdFrom, preTranslatedTo));
        edgeValueTypeBytesToValueConverter.setValue(valueTemplate, value);
        strm.write(valueTemplate);
    }

    public static long packEdges(int a, int b) {
        return ((long) a << 32) + b;
    }

    public static int getFirst(long l) {
        return  (int)  (l >> 32);
    }

    public static int getSecond(long l) {
        return (int) (l & 0x00000000ffffffffl);
    }


    public void process() throws  IOException {
        /* Check if we have enough memory to keep track of
           vertex degree in memory
         */

        // Ad-hoc: require that degree vertices won't take more than 5th of memory
        memoryEfficientDegreeCount = Runtime.getRuntime().maxMemory() / 5 <  ((long) maxVertexId) * 8;

        if (memoryEfficientDegreeCount) {
            logger.info("Going to use memory-efficient, but slower, method to compute vertex degrees.");
        }

        if (!memoryEfficientDegreeCount) {
            inDegrees = new int[maxVertexId + numShards];
            outDegrees = new int[maxVertexId + numShards];
        }
        finalIdTranslate = new VertexIdTranslate((1 + maxVertexId) / numShards + 1, numShards);
        saveVertexTranslate();

        for(int i=0; i < numShards; i++) {
            shovelStreams[i].close();
        }
        shovelStreams = null;

        writeIntervals();

        for(int i=0; i<numShards; i++) {
            processShovel(i);
        }

        if (!memoryEfficientDegreeCount) {
            writeDegrees();
        } else {
            computeVertexDegrees();
        }
    }

    private void writeDegrees() throws IOException {
        boolean useSparseDegrees = (maxVertexId > numEdges) || "1".equals(System.getProperty("sparsedeg"));

        DataOutputStream degreeOut = new DataOutputStream(new BufferedOutputStream(
                new FileOutputStream(ChiFilenames.getFilenameOfDegreeData(baseFilename, useSparseDegrees))));
        for(int i=0; i<inDegrees.length; i++) {
            if (!useSparseDegrees)   {
                degreeOut.writeInt(Integer.reverseBytes(inDegrees[i]));
                degreeOut.writeInt(Integer.reverseBytes(outDegrees[i]));
            } else {
                if (inDegrees[i] + outDegrees[i] > 0) {
                    degreeOut.writeInt(Integer.reverseBytes(i));
                    degreeOut.writeInt(Integer.reverseBytes(inDegrees[i]));
                    degreeOut.writeInt(Integer.reverseBytes(outDegrees[i]));
                }
            }
        }
        degreeOut.close();
    }

    private void writeIntervals() throws IOException{
        FileWriter wr = new FileWriter(ChiFilenames.getFilenameIntervals(baseFilename, numShards));
        for(int j=1; j<=numShards; j++) {
            wr.write((j * finalIdTranslate.getVertexIntervalLength() -1) + "\n");
        }
        wr.close();
    }

    private void saveVertexTranslate() throws IOException {
        FileWriter wr = new FileWriter(ChiFilenames.getVertexTranslateDefFile(baseFilename, numShards));
        wr.write(finalIdTranslate.stringRepresentation());
        wr.close();
    }

    private void processShovel(int shardNum) throws IOException {
        File shovelFile = new File(shovelFilename(shardNum));
        long[] shoveled = new long[(int) (shovelFile.length() / (8 + edgeValueTypeBytesToValueConverter.sizeOf()))];

        // TODO: improve
        if (shoveled.length > 500000000) {
            throw new RuntimeException("Too big shard size, shovel length was: " + shoveled.length + " max: " + 500000000);
        }
        int sizeOf = edgeValueTypeBytesToValueConverter.sizeOf();
        byte[] edgeValues = new byte[shoveled.length * sizeOf];


        logger.info("Processing shovel " + shardNum);

        BufferedDataInputStream in = new BufferedDataInputStream(new FileInputStream(shovelFile));
        for(int i=0; i<shoveled.length; i++) {
            long l = in.readLong();
            int from = getFirst(l);
            int to = getSecond(l);
            in.readFully(valueTemplate);

            int newFrom = finalIdTranslate.forward(preIdTranslate.backward(from));
            int newTo = finalIdTranslate.forward(preIdTranslate.backward(to));
            shoveled[i] = packEdges(newFrom, newTo);

            /* Edge value */
            int valueIdx = i * sizeOf;
            System.arraycopy(valueTemplate, 0, edgeValues, valueIdx, sizeOf);
            if (!memoryEfficientDegreeCount) {
                inDegrees[newTo]++;
                outDegrees[newFrom]++;
            }
        }
        numEdges += shoveled.length;

        in.close();

        shovelFile.delete();

        logger.info("Processing shovel " + shardNum + " ... sorting");

        sortWithValues(shoveled, edgeValues, sizeOf);  // The source id is  higher order, so sorting the longs will produce right result

        logger.info("Processing shovel " + shardNum + " ... writing shard");


        File adjFile = new File(ChiFilenames.getFilenameShardsAdj(baseFilename, shardNum, numShards));
        DataOutputStream adjOut = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(adjFile)));
        int curvid = 0;
        int istart = 0;
        for(int i=0; i < shoveled.length; i++) {
            int from = getFirst(shoveled[i]);
            if (from != curvid || i == shoveled.length - 1) {
                int count = i - istart;
                if (count > 0) {
                    if (count < 255) {
                        adjOut.writeByte(count);
                    } else {
                        adjOut.writeByte(0xff);
                        adjOut.writeInt(Integer.reverseBytes(count));
                    }
                }
                for(int j=istart; j<i; j++) {
                    adjOut.writeInt(Integer.reverseBytes(getSecond(shoveled[j])));
                }

                istart = i;

                // Handle zeros
                if (from - curvid > 1 || (i == 0 && from > 0)) {
                    int nz = from - curvid - 1;
                    if (i ==0 && from >0) nz = from;
                    do {
                        adjOut.writeByte(0);
                        nz--;
                        int tnz = Math.min(254, nz);
                        adjOut.writeByte(tnz);
                        nz -= tnz;
                    } while (nz > 0);
                }
                curvid = from;
            }
        }
        adjOut.close();

        /* Create compressed edge data directories */
        int blockSize = ChiFilenames.getBlocksize(edgeValueTypeBytesToValueConverter.sizeOf());


        String edataFileName = ChiFilenames.getFilenameShardEdata(baseFilename, new BytesToValueConverter() {
            @Override
            public int sizeOf() {
                return edgeValueTypeBytesToValueConverter.sizeOf();
            }

            @Override
            public Object getValue(byte[] array) {
                return null;
            }

            @Override
            public void setValue(byte[] array, Object val) {
            }
        }, shardNum, numShards);
        File edgeDataSizeFile = new File(edataFileName + ".size");
        File edgeDataDir = new File(ChiFilenames.getDirnameShardEdataBlock(edataFileName, blockSize));
        if (!edgeDataDir.exists()) edgeDataDir.mkdir();

        long edatasize = shoveled.length * edgeValueTypeBytesToValueConverter.sizeOf();
        FileWriter sizeWr = new FileWriter(edgeDataSizeFile);
        sizeWr.write(edatasize + "");
        sizeWr.close();

        /* Create blocks */
        int blockIdx = 0;
        int edgeIdx= 0;
        for(long idx=0; idx < edatasize; idx += blockSize) {
            File blockFile = new File(ChiFilenames.getFilenameShardEdataBlock(edataFileName, blockIdx, blockSize));
            DeflaterOutputStream blockOs = new DeflaterOutputStream(new BufferedOutputStream(new FileOutputStream(blockFile)));
            long len = Math.min(blockSize, edatasize - idx);
            byte[] block = new byte[(int)len];

            System.arraycopy(edgeValues, edgeIdx * sizeOf, block, 0, block.length);
            edgeIdx += len / sizeOf;

            blockOs.write(block);
            blockOs.close();
            blockIdx++;
        }

        assert(edgeIdx == edgeValues.length);
    }

    private static Random random = new Random();



    // http://www.algolist.net/Algorithms/Sorting/Quicksort
    // TODO: implement faster
    static int partition(long arr[], byte[] values, int sizeOf, int left, int right)
    {
        int i = left, j = right;
        long tmp;
        long pivot = arr[left + random.nextInt(right - left + 1)];
        byte[] valueTemplate = new byte[sizeOf];

        while (i <= j) {
            while (arr[i] < pivot)
                i++;
            while (arr[j] > pivot)
                j--;
            if (i <= j) {
                tmp = arr[i];

                /* Swap */
                System.arraycopy(values, j * sizeOf, valueTemplate, 0, sizeOf);
                System.arraycopy(values, i * sizeOf, values, j * sizeOf, sizeOf);
                System.arraycopy(valueTemplate, 0, values, i * sizeOf, sizeOf);

                arr[i] = arr[j];
                arr[j] = tmp;
                i++;
                j--;
            }
        }

        return i;
    }

    static void quickSort(long arr[], byte[] values, int sizeOf, int left, int right) {
        int index = partition(arr, values, sizeOf, left, right);
        if (left < index - 1)
            quickSort(arr, values, sizeOf, left, index - 1);
        if (index < right)
            quickSort(arr, values, sizeOf, index, right);
    }


    public static void sortWithValues(long[] shoveled, byte[] edgeValues, int sizeOf) {
        quickSort(shoveled, edgeValues, sizeOf, 0, shoveled.length - 1);
    }


    public void shard(InputStream inputStream) throws IOException {
        BufferedReader ins = new BufferedReader(new InputStreamReader(inputStream));
        String ln;
        long lineNum = 0;
        while ((ln = ins.readLine()) != null) {
            if (ln.length() > 2 && !ln.startsWith("#")) {
                lineNum++;
                if (lineNum % 2000000 == 0) System.out.println(lineNum);
                String[] tok = ln.split("\t");
                if (tok.length == 2) {
                    this.addEdge(Integer.parseInt(tok[0]), Integer.parseInt(tok[1]), null);
                } else if (tok.length == 3) {
                    this.addEdge(Integer.parseInt(tok[0]), Integer.parseInt(tok[1]), tok[2]);
                }
            }
        }
        this.process();
    }

    /**
     * Compute vertex degrees by running a special graphchi program
     */
    public void computeVertexDegrees() {
        try {

            boolean useSparseDegrees = (maxVertexId > numEdges) || "1".equals(System.getProperty("sparsedeg"));

            logger.info("Use sparse degrees: " + useSparseDegrees);

            DataOutputStream degreeOut = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(ChiFilenames.getFilenameOfDegreeData(baseFilename, useSparseDegrees))));


            SlidingShard[] slidingShards = new SlidingShard[numShards];
            for(int p=0; p < numShards; p++) {
                int intervalSt = p * finalIdTranslate.getVertexIntervalLength();
                int intervalEn = (p + 1) * finalIdTranslate.getVertexIntervalLength() - 1;

                slidingShards[p] = new SlidingShard(null, ChiFilenames.getFilenameShardsAdj(baseFilename, p, numShards),
                        intervalSt, intervalEn);
                slidingShards[p].setOnlyAdjacency(true);
            }

            int SUBINTERVAL = 2000000;

            for(int p=0; p < numShards; p++) {
                logger.info("Degree computation round " + p + " / " + numShards);
                int intervalSt = p * finalIdTranslate.getVertexIntervalLength();
                int intervalEn = (p + 1) * finalIdTranslate.getVertexIntervalLength() - 1;

                MemoryShard<Float> memoryShard = new MemoryShard<Float>(null, ChiFilenames.getFilenameShardsAdj(baseFilename, p, numShards),
                        intervalSt, intervalEn);
                memoryShard.setOnlyAdjacency(true);


                for(int subIntervalSt=intervalSt; subIntervalSt < intervalEn; subIntervalSt += SUBINTERVAL) {
                    int subIntervalEn = subIntervalSt + SUBINTERVAL - 1;
                    if (subIntervalEn > intervalEn) subIntervalEn = intervalEn;
                    ChiVertex[] verts = new ChiVertex[subIntervalEn - subIntervalSt + 1];
                    for(int i=0; i < verts.length; i++) {
                        verts[i] = new ChiVertex(i + subIntervalSt, null);
                    }

                    memoryShard.loadVertices(subIntervalSt, subIntervalEn, verts, false);
                    for(int i=0; i < numShards; i++) {
                        if (i != p) {
                            slidingShards[i].readNextVertices(verts, subIntervalSt, true);
                        }
                    }

                    for(int i=0; i < verts.length; i++) {
                        if (!useSparseDegrees) {
                            degreeOut.writeInt(Integer.reverseBytes(verts[i].numInEdges()));
                            degreeOut.writeInt(Integer.reverseBytes(verts[i].numOutEdges()));
                        } else {
                            if (verts[i].numEdges() > 0 ){
                                degreeOut.writeInt(Integer.reverseBytes(subIntervalSt + i));
                                degreeOut.writeInt(Integer.reverseBytes(verts[i].numInEdges()));
                                degreeOut.writeInt(Integer.reverseBytes(verts[i].numOutEdges()));
                            }
                        }
                    }
                }
            }
            degreeOut.close();
        } catch (Exception err) {
            err.printStackTrace();
        }
    }
}
