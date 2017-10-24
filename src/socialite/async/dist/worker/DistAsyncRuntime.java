package socialite.async.dist.worker;

import mpi.MPI;
import mpi.MPIException;
import mpi.Status;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import socialite.async.AsyncConfig;
import socialite.async.codegen.BaseAsyncRuntime;
import socialite.async.codegen.BaseDistAsyncTable;
import socialite.async.codegen.MessageTableBase;
import socialite.async.dist.MsgType;
import socialite.async.dist.Payload;
import socialite.async.dist.master.AsyncMaster;
import socialite.async.util.SerializeTool;
import socialite.parser.Table;
import socialite.resource.DistTableSliceMap;
import socialite.resource.SRuntimeWorker;
import socialite.resource.TableInstRegistry;
import socialite.tables.TableInst;
import socialite.util.Assert;
import socialite.util.Loader;
import socialite.util.SociaLiteException;
import socialite.visitors.VisitorImpl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;

public class DistAsyncRuntime extends BaseAsyncRuntime {
    private static final Log L = LogFactory.getLog(DistAsyncRuntime.class);
    private final int myWorkerId;
    private final int workerNum;
    private SendThread[] sendThreads;
    private ReceiveThread[] receiveThreads;
    private Payload payload;
    private boolean stopNetworkThread;

    DistAsyncRuntime() {
        workerNum = MPI.COMM_WORLD.Size() - 1;
        myWorkerId = MPI.COMM_WORLD.Rank() - 1;
    }

    @Override
    public void run() {
        waitingCmd();//waiting for AsyncConfig
        SRuntimeWorker runtimeWorker = SRuntimeWorker.getInst();
        TableInstRegistry tableInstRegistry = runtimeWorker.getTableRegistry();
        Map<String, Table> tableMap = runtimeWorker.getTableMap();
        TableInst[] initTableInstArr = tableInstRegistry.getTableInstArray(tableMap.get("InitTable").id());
        TableInst[] edgeTableInstArr = tableInstRegistry.getTableInstArray(tableMap.get(payload.getEdgeTableName()).id());
        if (loadData(initTableInstArr, edgeTableInstArr)) {//this worker is idle, stop
            createThreads();
            startThreads();
        } else {//this worker is idle
            throw new SociaLiteException("Worker " + myWorkerId + " is idle, please reduce the number of workers");
        }
    }

    private void waitingCmd() {
        byte[] data = new byte[1024 * 1024];
        MPI.COMM_WORLD.Sendrecv(new int[]{SRuntimeWorker.getInst().getWorkerAddrMap().myIndex(), myWorkerId}, 0, 2, MPI.INT, AsyncMaster.ID, MsgType.REPORT_MYIDX.ordinal(),
                data, 0, data.length, MPI.BYTE, AsyncMaster.ID, MsgType.NOTIFY_INIT.ordinal());
        SerializeTool serializeTool = new SerializeTool.Builder().build();
        payload = serializeTool.fromBytes(data, Payload.class);
        AsyncConfig.set(payload.getAsyncConfig());
        L.info("RECV CMD NOTIFY_INIT CONFIG:" + AsyncConfig.get());
    }

    @Override
    protected boolean loadData(TableInst[] initTableInstArr, TableInst[] edgeTableInstArr) {
        Loader.loadFromBytes(payload.getByteCodes());
        Class<?> messageTableClass = Loader.forName("socialite.async.codegen.MessageTable");
        Class<?> distAsyncTableClass = Loader.forName("socialite.async.codegen.DistAsyncTable");
        try {
            SRuntimeWorker runtimeWorker = SRuntimeWorker.getInst();
            DistTableSliceMap sliceMap = runtimeWorker.getSliceMap();
            Map<Integer, Integer> myIdxWorkerMap = payload.getMyIdxWorkerIdMap();
            int[] myIdxWorkerArr = new int[myIdxWorkerMap.size()];
            myIdxWorkerMap.forEach((myIdx, workerId) -> myIdxWorkerArr[myIdx] = workerId);
            //static, int type key
            int indexForTableId;
            if (AsyncConfig.get().isDynamic()) {
                TableInst edgeInst = Arrays.stream(edgeTableInstArr).filter(tableInst -> !tableInst.isEmpty()).findFirst().orElse(null);
                if (edgeInst == null) {
                    edgeInst = edgeTableInstArr[0];
                    L.warn("worker " + myWorkerId + " has no job");
                }

                Method method = edgeInst.getClass().getMethod("tableid");
                indexForTableId = (Integer) method.invoke(edgeInst);
                //public DistAsyncTable(Class\<?> messageTableClass, DistTableSliceMap sliceMap, int indexForTableId) {
                Constructor constructor = distAsyncTableClass.getConstructor(messageTableClass.getClass(), DistTableSliceMap.class, int.class, int[].class);

                asyncTable = (BaseDistAsyncTable) constructor.newInstance(messageTableClass, sliceMap, indexForTableId, myIdxWorkerArr);
                //动态算法需要edge做连接，如prog4、9!>
                method = edgeTableInstArr[0].getClass().getDeclaredMethod("iterate", VisitorImpl.class);
                int edgeTableNum = 0;
                for (TableInst tableInst : edgeTableInstArr) {
                    if (!tableInst.isEmpty()) {
                        method.invoke(tableInst, asyncTable.getEdgeVisitor());
                        tableInst.clear();
                        edgeTableNum++;
                    }
                }
                if (edgeTableNum > 1)
                    Assert.impossible();
            } else {
                TableInst initTableInst = Arrays.stream(initTableInstArr).filter(tableInst -> !tableInst.isEmpty()).findFirst().orElse(null);
                if (initTableInst == null) {
                    L.warn("worker " + myWorkerId + " has no job");
                    return false;
                }
                Method method = initTableInst.getClass().getMethod("tableid");
                indexForTableId = (Integer) method.invoke(initTableInst);
                Field baseField = initTableInstArr[0].getClass().getDeclaredField("base");
                baseField.setAccessible(true);
                int base = baseField.getInt(Arrays.stream(initTableInstArr).filter(tableInst -> !tableInst.isEmpty()).findFirst().orElse(null));
                //public DistAsyncTable(Class\<?> messageTableClass, DistTableSliceMap sliceMap, int indexForTableId, int base) {
                Constructor constructor = distAsyncTableClass.getConstructor(messageTableClass.getClass(), DistTableSliceMap.class, int.class, int[].class, int.class);
                asyncTable = (BaseDistAsyncTable) constructor.newInstance(messageTableClass, sliceMap, indexForTableId, myIdxWorkerArr, base);
            }


            Method method = initTableInstArr[0].getClass().getDeclaredMethod("iterate", VisitorImpl.class);
            int initTableNum = 0;
            for (TableInst tableInst : initTableInstArr) {
                if (!tableInst.isEmpty()) {
                    method.invoke(tableInst, asyncTable.getInitVisitor());
                    tableInst.clear();
                    initTableNum++;
                }
            }
            if (initTableNum > 1)
                Assert.impossible();
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | InstantiationException | NoSuchFieldException e) {
            e.printStackTrace();
        }
        L.info("WorkerId " + myWorkerId + " Data Loaded size:" + asyncTable.getSize());
        return true;
    }

    @Override
    protected void createThreads() {
        super.createThreads();
        checkerThread = new CheckThread();
        createNetworkThreads();
        if (AsyncConfig.get().isSync() || AsyncConfig.get().isBarrier())
            barrier = new CyclicBarrier(asyncConfig.getThreadNum(), checkerThread);
    }

    private void createNetworkThreads() {
        sendThreads = new SendThread[workerNum];
        receiveThreads = new ReceiveThread[workerNum];
        for (int wid = 0; wid < workerNum; wid++) {
            if (wid == myWorkerId) continue; //skip me
            sendThreads[wid] = new SendThread(wid);
            receiveThreads[wid] = new ReceiveThread(wid);
        }
    }

    private void startThreads() {
        Arrays.stream(computingThreads).filter(Objects::nonNull).forEach(Thread::start);
        if (!AsyncConfig.get().isSync() && !AsyncConfig.get().isBarrier()) {
            Arrays.stream(receiveThreads).filter(Objects::nonNull).forEach(Thread::start);
            Arrays.stream(sendThreads).filter(Objects::nonNull).forEach(Thread::start);
            checkerThread.start();
        }

        if (AsyncConfig.get().isPriority() && !AsyncConfig.get().isPriorityLocal()) schedulerThread.start();
        L.info(String.format("Worker %d all threads started.", myWorkerId));
        try {
            for (ComputingThread computingThread : computingThreads) computingThread.join();
            L.info("Worker " + myWorkerId + " Computing Threads exited.");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private class SendThread extends Thread {
        private final int sendToWorkerId;
        private SerializeTool serializeTool;

        private SendThread(int sendToWorkerId) {
            this.sendToWorkerId = sendToWorkerId;
            serializeTool = new SerializeTool.Builder()
                    .setSerializeTransient(true) //!!!!!!!!!!AtomicDouble's value field is transient
                    .build();
        }

        @Override
        public void run() {
            try {
                while (!stopNetworkThread) {
                    byte[] data = ((BaseDistAsyncTable) asyncTable).getSendableMessageTableBytes(sendToWorkerId, serializeTool);
                    MPI.COMM_WORLD.Send(data, 0, data.length, MPI.BYTE, sendToWorkerId + 1, MsgType.MESSAGE_TABLE.ordinal());
                    if (AsyncConfig.get().isSync() || AsyncConfig.get().isBarrier()) break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class ReceiveThread extends Thread {
        private SerializeTool serializeTool;
        private int recvFromWorkerId;
        private Class<?> klass;

        private ReceiveThread(int recvFromWorkerId) {
            this.recvFromWorkerId = recvFromWorkerId;
            serializeTool = new SerializeTool.Builder()
                    .setSerializeTransient(true)
                    .build();
            klass = Loader.forName("socialite.async.codegen.MessageTable");
        }

        @Override
        public void run() {
            try {
                while (!stopNetworkThread) {
                    Status status = MPI.COMM_WORLD.Probe(recvFromWorkerId + 1, MsgType.MESSAGE_TABLE.ordinal());
                    int size = status.Get_count(MPI.BYTE);
                    int source = status.source;
                    byte[] data = new byte[size];
                    MPI.COMM_WORLD.Recv(data, 0, size, MPI.BYTE, source, MsgType.MESSAGE_TABLE.ordinal());
                    MessageTableBase messageTable = (MessageTableBase) serializeTool.fromBytesToObject(data, klass);
                    ((BaseDistAsyncTable) asyncTable).applyBuffer(messageTable);
                    if (AsyncConfig.get().isSync() || AsyncConfig.get().isBarrier()) break;
                }
            } catch (MPIException e) {
                e.printStackTrace();
            }
        }
    }

    private class CheckThread extends BaseAsyncRuntime.CheckThread {
        private AsyncConfig asyncConfig;


        private CheckThread() {
            asyncConfig = AsyncConfig.get();
        }

        @Override
        public void run() {
            super.run();
            boolean[] feedback = new boolean[2];
            while (true) {
                if (asyncConfig.isSync() || asyncConfig.isBarrier()) {//sync mode
                    Arrays.stream(receiveThreads).filter(Objects::nonNull).forEach(ReceiveThread::start);
                    Arrays.stream(sendThreads).filter(Objects::nonNull).forEach(SendThread::start);
                    Arrays.stream(receiveThreads).filter(Objects::nonNull).forEach(recvThread -> {
                        try {
                            recvThread.join();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    });
                    Arrays.stream(sendThreads).filter(Objects::nonNull).forEach(sendThread -> {
                        try {
                            sendThread.join();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    });


                    double partialSum = update();
                    MPI.COMM_WORLD.Sendrecv(new double[]{partialSum, updateCounter.get()}, 0, 2, MPI.DOUBLE, AsyncMaster.ID, MsgType.REQUIRE_TERM_CHECK.ordinal(),
                            feedback, 0, 1, MPI.BOOLEAN, AsyncMaster.ID, MsgType.TERM_CHECK_FEEDBACK.ordinal());
                    if (feedback[0]) {
                        flush();
                        done();
                    } else {
                        createNetworkThreads();//cannot reuse dead thread, we need recreate
                    }
                    break;//exit function, run will be called next round
                } else {
                    double partialSum = update();
                    MPI.COMM_WORLD.Recv(new byte[1], 0, 1, MPI.BYTE, AsyncMaster.ID, MsgType.REQUIRE_TERM_CHECK.ordinal());
                    MPI.COMM_WORLD.Sendrecv(new double[]{partialSum, updateCounter.get()}, 0, 2, MPI.DOUBLE, AsyncMaster.ID, MsgType.TERM_CHECK_PARTIAL_VALUE.ordinal(),
                            feedback, 0, 1, MPI.BOOLEAN, AsyncMaster.ID, MsgType.TERM_CHECK_FEEDBACK.ordinal());
                    if (feedback[0]) {
                        flush();
                        done();
                        break;
                    }
                }
            }
        }

        private double update() {
            double partialSum = 0;
            if (asyncTable != null) {//null indicate this worker is idle
                if (asyncConfig.getCheckType() == AsyncConfig.CheckerType.DELTA || asyncConfig.getCheckType() == AsyncConfig.CheckerType.DIFF_DELTA) {
                    partialSum = asyncTable.accumulateDelta();
                    BaseDistAsyncTable baseDistAsyncTable = (BaseDistAsyncTable) asyncTable;
                    for (int workerId = 0; workerId < workerNum; workerId++) {
                        MessageTableBase messageTable1 = baseDistAsyncTable.getMessageTableList()[workerId][0];
                        MessageTableBase messageTable2 = baseDistAsyncTable.getMessageTableList()[workerId][1];
                        if (messageTable1 == null || messageTable2 == null) continue;
                        partialSum += messageTable1.accumulate();
                        partialSum += messageTable2.accumulate();
                    }
//                    L.info("partialSum of delta: " + new BigDecimal(partialSum));
                } else if (asyncConfig.getCheckType() == AsyncConfig.CheckerType.VALUE || asyncConfig.getCheckType() == AsyncConfig.CheckerType.DIFF_VALUE) {
                    partialSum = asyncTable.accumulateValue();
//                    L.info("sum of value: " + new BigDecimal(partialSum));
                }
                //accumulate rest message
            }
            return partialSum;
        }

        //ensure all messages flushed to remote workers
        private void flush() {
            int sleepTime = asyncConfig.getMessageTableWaitingInterval() + 1000;
            L.info("Wait " + sleepTime + "ms to flush");
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            stopNetworkThread = true;
        }
    }


}
