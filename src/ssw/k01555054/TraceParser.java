package ssw.k01555054;

import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.List;

public class TraceParser {

    private static final int SOURCE_SECTION_SIZE = 8;

    private enum TraceRecords {
        ActivityCreation(11 + SOURCE_SECTION_SIZE),
        ActivityCompletion(1),
        DynamicScopeStart(9 + SOURCE_SECTION_SIZE),
        DynamicScopeEnd(1),
        PassiveEntityCreation(9 + SOURCE_SECTION_SIZE),
        PassiveEntityCompletion(0),
        SendOp(17),
        ReceiveOp(9),
        ImplThread(9),
        ImplThreadCurrentActivity(13);

        private int byteSize;

        TraceRecords(int byteSize) {
            this.byteSize = byteSize;
        }

        public int getByteSize() {
            return byteSize;
        }
    }

    private final TraceRecords[] parseTable = createParseTable();
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(2048);

    private final HashMap<Long, MsgObj> messages = new HashMap<>();
    private final Deque<Long> turnStack = new ArrayDeque<Long>();


    private TraceRecords[] createParseTable() {
        TraceRecords[] result = new TraceRecords[23];

        result[Marker.PROCESS_CREATION] = TraceRecords.ActivityCreation;
        result[Marker.PROCESS_COMPLETION] = TraceRecords.ActivityCompletion;
        result[Marker.ACTOR_CREATION] = TraceRecords.ActivityCreation;
        result[Marker.TASK_SPAWN] = TraceRecords.ActivityCreation;
        result[Marker.THREAD_SPAWN] = TraceRecords.ActivityCreation;

        result[Marker.ACTOR_MSG_SEND] = TraceRecords.SendOp;
        result[Marker.PROMISE_MSG_SEND] = TraceRecords.SendOp;
        result[Marker.CHANNEL_MSG_SEND] = TraceRecords.SendOp;
        result[Marker.PROMISE_RESOLUTION] = TraceRecords.SendOp;

        result[Marker.CHANNEL_MSG_RCV] = TraceRecords.ReceiveOp;
        result[Marker.TASK_JOIN] = TraceRecords.ReceiveOp;
        result[Marker.THREAD_JOIN] = TraceRecords.ReceiveOp;

        result[Marker.TURN_START] = TraceRecords.DynamicScopeStart;
        result[Marker.TURN_END] = TraceRecords.DynamicScopeEnd;
        result[Marker.MONITOR_ENTER] = TraceRecords.DynamicScopeStart;
        result[Marker.MONITOR_EXIT] = TraceRecords.DynamicScopeEnd;
        result[Marker.TRANSACTION_START] = TraceRecords.DynamicScopeStart;
        result[Marker.TRANSACTION_END] = TraceRecords.DynamicScopeEnd;

        result[Marker.CHANNEL_CREATION] = TraceRecords.PassiveEntityCreation;
        result[Marker.PROMISE_CREATION] = TraceRecords.PassiveEntityCreation;

        result[Marker.IMPL_THREAD] = TraceRecords.ImplThread;
        result[Marker.IMPL_THREAD_CURRENT_ACTIVITY] = TraceRecords.ImplThreadCurrentActivity;

        return result;
    }

    public void parse(String path) {
        File traceFile = new File(path);

        long trackingId = -1;
        try {
            FileInputStream fis = new FileInputStream(traceFile);
            FileChannel channel = fis.getChannel();

            channel.read(byteBuffer);
            byteBuffer.flip();

            long currentActivityId = -1;

            int testCount = 0;

            while(channel.position() < channel.size() || byteBuffer.remaining() > 0) {
                if (!byteBuffer.hasRemaining()) {
                    byteBuffer.clear();
                    channel.read(byteBuffer);
                    byteBuffer.flip();
                } else if (byteBuffer.remaining() < 20) {
                    byteBuffer.compact();
                    channel.read(byteBuffer);
                    byteBuffer.flip();
                }

                final int start = byteBuffer.position();
                final byte type = byteBuffer.get();

                TraceRecords recordType = parseTable[type];

                switch (recordType) {
                    case ActivityCreation:
                        long activityId = byteBuffer.getLong();
                        short symboldId = byteBuffer.getShort();
                        readSourceSection();
                        assert byteBuffer.position() == start + (TraceRecords.ActivityCreation.getByteSize() + 1);
                        break;
                    case ActivityCompletion:
                        assert byteBuffer.position() == start + (TraceRecords.ActivityCompletion.getByteSize() + 1);
                        break;
                    case DynamicScopeStart:
                        long id = byteBuffer.getLong();
                        if(type == Marker.TURN_START) {
                            turnStack.push(id);
                        }
                        readSourceSection();
                        assert byteBuffer.position() == start + (TraceRecords.DynamicScopeStart.getByteSize() + 1);
                        break;
                    case DynamicScopeEnd:
                        if(type == Marker.TURN_END) {
                            turnStack.remove();
                        }
                        assert byteBuffer.position() == start + (TraceRecords.DynamicScopeEnd.getByteSize() + 1);
                        break;
                    case SendOp:
                        long entityId = byteBuffer.getLong();
                        long targetId = byteBuffer.getLong();
                        if (type == Marker.ACTOR_MSG_SEND) {
                            if (turnStack.size() > 0 /* && entityId != turnStack.peek() */) {
                                if(entityId == turnStack.peek()) {
                                    System.out.println("Turn same as send...");
                                }
                                messages.put(entityId, new MsgObj(entityId ,currentActivityId, targetId, turnStack.peek()));
                            } else {
                                messages.put(entityId, new MsgObj(entityId ,currentActivityId, targetId));
                            }

                            if(testCount == 1)
                                trackingId = entityId;

                            testCount++;
                        }

                        assert byteBuffer.position() == start + (TraceRecords.SendOp.getByteSize() + 1);
                        break;
                    case ReceiveOp:
                        long sourceId = byteBuffer.getLong();
                        assert byteBuffer.position() == start + (TraceRecords.ReceiveOp.getByteSize() + 1);
                    case PassiveEntityCreation:
                        id = byteBuffer.getLong();
                        readSourceSection();
                        assert byteBuffer.position() == start + (TraceRecords.PassiveEntityCreation.getByteSize() + 1);
                        break;
                    case PassiveEntityCompletion:
                        assert byteBuffer.position() == start + (TraceRecords.PassiveEntityCompletion.getByteSize() + 1);
                        break;
                    case ImplThread:
                        long currentImplThreadId = byteBuffer.getLong();
                        assert byteBuffer.position() == start + (TraceRecords.ImplThread.getByteSize() + 1);
                        break;
                    case ImplThreadCurrentActivity:
                        currentActivityId = byteBuffer.getLong();
                        int currentActivityBufferId = byteBuffer.getInt();
                        assert byteBuffer.position() == start + (TraceRecords.ImplThreadCurrentActivity.getByteSize() + 1);
                        break;
                    default:
                        assert false;
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        MsgObj message = messages.get(trackingId);

        while(message.parentMsgId != -1) {
            System.out.println("Going up");
            if (messages.containsKey(message.parentMsgId)) {
                message = messages.get(message.parentMsgId);
            } else {
                System.out.println("Message not found?!");
                break;
            }
        }
    }

    private void readSourceSection() {
        short fileId = byteBuffer.getShort();
        short startLine = byteBuffer.getShort();
        short startCol = byteBuffer.getShort();
        short charLen = byteBuffer.getShort();
    }
}
