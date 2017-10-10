package socialite.async.codegen;


import mpi.MPI;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import socialite.async.AsyncConfig;
import socialite.async.analysis.MyVisitorImpl;
import socialite.async.util.SerializeTool;
import socialite.engine.Config;
import socialite.resource.DistTableSliceMap;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;

public abstract class BaseDistAsyncTable extends BaseAsyncTable {
    private final AtomicIntegerArray messageTableSelector;
    private final MessageTableBase[][] messageTableList;
    protected final int workerNum;
    protected final int myWorkerId;
    protected final DistTableSliceMap sliceMap;
    protected final int indexForTableId;
    protected final Map<Integer, Integer> myIdxWorkerIdMap;

    protected final int messageTableUpdateThreshold;
    protected final int initSize;

    public BaseDistAsyncTable(Class<?> messageTableClass, DistTableSliceMap sliceMap, int indexForTableId, Map<Integer, Integer> myIdxWorkerIdMap) {
        this.workerNum = Config.getWorkerNodeNum();
        this.myWorkerId = MPI.COMM_WORLD.Rank() - 1;
        this.sliceMap = sliceMap;
        this.indexForTableId = indexForTableId;
        this.myIdxWorkerIdMap = myIdxWorkerIdMap;
        this.messageTableUpdateThreshold = AsyncConfig.get().getMessageTableUpdateThreshold();
        this.initSize = AsyncConfig.get().getInitSize();
        int messageTableInitSize = AsyncConfig.get().getMessageTableInitSize();


        messageTableSelector = new AtomicIntegerArray(workerNum);
        messageTableList = new MessageTableBase[workerNum][2];
        try {
            Constructor constructor = messageTableClass.getConstructor(int.class);

            for (int wid = 0; wid < workerNum; wid++) {
                if (wid == myWorkerId) continue;//for worker i, it have 0,1,...,i-1,null,i+1,...n-1 buffer table
                messageTableList[wid][0] = (MessageTableBase) constructor.newInstance(messageTableInitSize);
                messageTableList[wid][1] = (MessageTableBase) constructor.newInstance(messageTableInitSize);
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | InstantiationException e) {
            e.printStackTrace();
        }

    }

    public MessageTableBase[] getMessageTables(int workerId) {
        return messageTableList[workerId];
    }

    public AtomicIntegerArray getMessageTableSelector() {
        return messageTableSelector;
    }

    public MessageTableBase getWritableMessageTable(int workerId) {
        return messageTableList[workerId][messageTableSelector.get(workerId)];
    }

    private static final Log L = LogFactory.getLog(BaseDistAsyncTable.class);

    public byte[] getSendableMessageTableBytes(int sendToWorkerId, SerializeTool serializeTool) throws InterruptedException {
        int writingTableInd;
        writingTableInd = messageTableSelector.get(sendToWorkerId);//获取计算线程正在写入的表序号
        MessageTableBase sendableMessageTable = messageTableList[sendToWorkerId][writingTableInd];
        long startTime = System.currentTimeMillis();
        while (sendableMessageTable.getUpdateTimes() < messageTableUpdateThreshold ||
                (System.currentTimeMillis() - startTime) >= AsyncConfig.get().getMessageTableWaitingInterval()) {
            Thread.sleep(100);
        }
        messageTableSelector.set(sendToWorkerId, writingTableInd == 0 ? 1 : 0);
        byte[] data = serializeTool.toBytes(sendableMessageTable);
        sendableMessageTable.resetDelta();
        return data;
    }

    public abstract void applyBuffer(MessageTableBase messageTable);

    public MyVisitorImpl getEdgeVisitor() {
        throw new NotImplementedException();
    }

    public MyVisitorImpl getInitVisitor() {
        throw new NotImplementedException();
    }
}
