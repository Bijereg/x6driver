package dev.sbelx.x6driver.core;
import java.util.*;
public final class UndoHistory {
    private final Deque<Document> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    public void checkpoint(Document d){undo.push(d.copy());while(undo.size()>35)undo.removeLast();redo.clear();}
    public Document undo(Document d){if(undo.isEmpty())return d;redo.push(d.copy());return undo.pop();}
    public Document redo(Document d){if(redo.isEmpty())return d;undo.push(d.copy());return redo.pop();}
    public void clear(){undo.clear();redo.clear();}
}
