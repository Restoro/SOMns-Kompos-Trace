package ssw.k01555054;

public class MsgObj {
    long messageId;
    long senderId;
    long receiverId;
    long parentMsgId;

    public MsgObj(long messageId, long senderId, long receiverId) {
        this.messageId = messageId;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.parentMsgId = -1;
    }

    public MsgObj(long messageId,long senderId, long receiverId, long parentMsgId) {
        this.messageId = messageId;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.parentMsgId = parentMsgId;
    }
}
