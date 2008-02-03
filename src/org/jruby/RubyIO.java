/*
 **** BEGIN LICENSE BLOCK *****
 * Version: CPL 1.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Common Public
 * License Version 1.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/cpl-v10.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004-2006 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2006 Evan Buswell <ebuswell@gmail.com>
 * Copyright (C) 2007 Miguel Covarrubias <mlcovarrubias@gmail.com>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the CPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the CPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/
package org.jruby;

import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.Pipe;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jruby.anno.JRubyMethod;
import org.jruby.common.IRubyWarnings.ID;

import org.jruby.runtime.Block;
import org.jruby.runtime.CallbackFactory;
import org.jruby.runtime.MethodIndex;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.Stream;
import org.jruby.util.Stream.InvalidValueException;
import org.jruby.util.Stream.PipeException;
import org.jruby.util.IOModes;
import org.jruby.util.ShellLauncher;
import org.jruby.util.TypeConverter;
import org.jruby.util.Stream.BadDescriptorException;
import org.jruby.util.ChannelStream;
import org.jruby.util.io.SplitChannel;

/**
 * 
 * @author jpetersen
 */
public class RubyIO extends RubyObject {
    public enum STDIO {
        IN, OUT, ERR;
        
        public int fileno() {
            switch (this) {
            case IN: return 0;
            case OUT: return 1;
            case ERR: return 2;
            default: throw new RuntimeException();
            }
        }
        
        public static boolean isSTDIO(int fileno) {
            if (fileno >= 0 && fileno <= 2) return true;
            
            return false;
        }
    }
    
    public static class OpenFile {
        private Stream mainStream;
        private Stream pipeStream;
        private IOModes modes;
        private int pid = -1; // probably not useful in JRuby; for fork and pipes and all
        private int lineNumber = 0;
        private String path;
        private Finalizer finalizer;

        public Stream getMainStream() {
            return mainStream;
        }

        public void setMainStream(Stream mainStream) {
            this.mainStream = mainStream;
        }

        public Stream getPipeStream() {
            return pipeStream;
        }

        public void setPipeStream(Stream pipeStream) {
            this.pipeStream = pipeStream;
        }

        public IOModes getModes() {
            return modes;
        }

        public void setModes(IOModes modes) {
            this.modes = modes;
        }

        public int getPid() {
            return pid;
        }

        public void setPid(int pid) {
            this.pid = pid;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public Finalizer getFinalizer() {
            return finalizer;
        }

        public void setFinalizer(Finalizer finalizer) {
            this.finalizer = finalizer;
        }
    }
    
    public static class ChannelDescriptor {
        private Channel channel;
        private int fileno;
        
        public ChannelDescriptor(Channel channel, int fileno) {
            this.channel = channel;
            this.fileno = fileno;
        }

        public int getFileno() {
            return fileno;
        }

        public void setFileno(int fileno) {
            this.fileno = fileno;
        }

        public Channel getChannel() {
            return channel;
        }

        public void setChannel(Channel channel) {
            this.channel = channel;
        }
        
        public boolean isSeekable() {
            return channel instanceof FileChannel;
        }
    }
    
    protected OpenFile openFile;
    
    // Does THIS IO object think it is still open
    // as opposed to the IO Handler which knows the
    // actual truth.  If two IO objects share the
    // same IO Handler, then it is possible for
    // one object to think that the handler is open
    // when it really isn't.  Keeping track of this yields
    // the right errors.
    protected boolean isOpen = true;
    private boolean atEOF = false;

    /*
     * Random notes:
     *  
     * 1. When a second IO object is created with the same fileno odd
     * concurrency issues happen when the underlying implementation
     * commits data.   So:
     * 
     * f = File.new("some file", "w")
     * f.puts("heh")
     * g = IO.new(f.fileno)
     * g.puts("hoh")
     * ... more operations of g and f ...
     * 
     * Will generate a mess in "some file".  The problem is that most
     * operations are buffered.  When those buffers flush and get
     * written to the physical file depends on the implementation
     * (semantically I would think that it should be last op wins -- but 
     * it isn't).  I doubt java could mimic ruby in this way.  I also 
     * doubt many people are taking advantage of this.  How about 
     * syswrite/sysread though?  I think the fact that sysread/syswrite 
     * are defined to be a low-level system calls, allows implementations 
     * to be somewhat different?
     * 
     * 2. In the case of:
     * f = File.new("some file", "w")
     * f.puts("heh")
     * print f.pos
     * g = IO.new(f.fileno)
     * print g.pos
     * Both printed positions will be the same.  But:
     * f = File.new("some file", "w")
     * f.puts("heh")
     * g = IO.new(f.fileno)
     * print f.pos, g.pos
     * won't be the same position.  Seem peculiar enough not to touch
     * (this involves pos() actually causing a seek?)
     * 
     * 3. All IO objects reference a IOHandler.  If multiple IO objects
     * have the same fileno, then they also share the same IOHandler.
     * It is possible that some IO objects may share the same IOHandler
     * but not have the same permissions.  However, all subsequent IO
     * objects created after the first must be a subset of the original
     * IO Object (see below for an example). 
     *
     * The idea that two or more IO objects can have different access
     * modes means that IO objects must keep track of their own
     * permissions.  In addition the IOHandler itself must know what
     * access modes it has.
     * 
     * The above sharing situation only occurs in a situation like:
     * f = File.new("some file", "r+")
     * g = IO.new(f.fileno, "r")
     * Where g has reduced (subset) permissions.
     * 
     * On reopen, the fileno's IOHandler gets replaced by a new handler. 
     */
    
    /*
     * I considered making all callers of this be moved into IOHandlers
     * constructors (since it would be less error prone to forget there).
     * However, reopen() makes doing this a little funky. 
     */
    public void registerIOHandler(Stream newHandler) {
        getRuntime().getIoHandlers().put(new Integer(newHandler.getDescriptor().getFileno()), new WeakReference<Stream>(newHandler));
    }
    
    public void unregisterIOHandler(int aFileno) {
        getRuntime().getIoHandlers().remove(new Integer(aFileno));
    }
    
    public Stream getIOHandlerByFileno(int aFileno) {
        Reference<Stream> reference = getRuntime().getIoHandlers().get(new Integer(aFileno));
        if (reference == null) {
            return null;
        }
        return (Stream) reference.get();
    }
    
    // FIXME can't use static; would interfere with other runtimes in the same JVM
    protected static int filenoIndex = 2;
    
    public static int getNewFileno() {
        filenoIndex++;
        
        return filenoIndex;
    }

    // This should only be called by this and RubyFile.
    // It allows this object to be created without a IOHandler.
    public RubyIO(Ruby runtime, RubyClass type) {
        super(runtime, type);
        
        openFile = new OpenFile();
        openFile.setModes(new IOModes(getRuntime()));
    }

    public RubyIO(Ruby runtime, OutputStream outputStream) {
        super(runtime, runtime.getIO());
        
        // We only want IO objects with valid streams (better to error now). 
        if (outputStream == null) {
            throw runtime.newIOError("Opening invalid stream");
        }
        
        openFile = new OpenFile();
        
        try {
            openFile.setMainStream(new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(outputStream), getNewFileno())));
        } catch (IOException e) {
            throw runtime.newIOError(e.getMessage());
        }
        openFile.setModes(openFile.getMainStream().getModes());
        
        registerIOHandler(openFile.getMainStream());
    }
    
    public RubyIO(Ruby runtime, InputStream inputStream) {
        super(runtime, runtime.getIO());
        
        if (inputStream == null) {
            throw runtime.newIOError("Opening invalid stream");
        }
        
        openFile = new OpenFile();
        
        try {
            openFile.setMainStream(new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(inputStream), getNewFileno())));
        } catch (IOException e) {
            throw runtime.newIOError(e.getMessage());
        }
        
        openFile.setModes(openFile.getMainStream().getModes());
        
        registerIOHandler(openFile.getMainStream());
    }
    
    public RubyIO(Ruby runtime, Channel channel) {
        super(runtime, runtime.getIO());
        
        // We only want IO objects with valid streams (better to error now). 
        if (channel == null) {
            throw runtime.newIOError("Opening invalid stream");
        }
        
        openFile = new OpenFile();
        
        try {
            openFile.setMainStream(new ChannelStream(runtime, new ChannelDescriptor(channel, getNewFileno())));
        } catch (IOException e) {
            throw runtime.newIOError(e.getMessage());
        }
        openFile.setModes(openFile.getMainStream().getModes());
        
        registerIOHandler(openFile.getMainStream());
    }

    public RubyIO(Ruby runtime, Process process) {
    	super(runtime, runtime.getIO());
        
        openFile = new OpenFile();
        
        openFile.setModes(new IOModes(runtime, "w+"));

        try {
            SplitChannel channel = new SplitChannel(
                    Channels.newChannel(process.getInputStream()),
                    Channels.newChannel(process.getOutputStream()));
    	    openFile.setMainStream(new ChannelStream(runtime, new ChannelDescriptor(channel, getNewFileno()),openFile.getModes()));
        } catch (IOException e) {
            throw runtime.newIOError(e.getMessage());
        }
    	openFile.setModes(openFile.getMainStream().getModes());
    	
    	registerIOHandler(openFile.getMainStream());
    }
    
    public RubyIO(Ruby runtime, STDIO stdio) {
        super(runtime, runtime.getIO());
        
        openFile = new OpenFile();

        try {
            switch (stdio) {
            case IN:
                openFile.setMainStream(new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(runtime.getIn()), 0), new IOModes(runtime, IOModes.RDONLY), FileDescriptor.in));
                break;
            case OUT:
                openFile.setMainStream(new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(runtime.getOut()), 1), new IOModes(runtime, IOModes.WRONLY | IOModes.APPEND), FileDescriptor.out));
                openFile.getMainStream().setSync(true);
                break;
            case ERR:
                openFile.setMainStream(new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(runtime.getErr()), 2), new IOModes(runtime, IOModes.WRONLY | IOModes.APPEND), FileDescriptor.err));
                openFile.getMainStream().setSync(true);
                break;
            }
        } catch (IOException e) {
            throw runtime.newErrnoEBADFError();
        }
        
        openFile.setModes(openFile.getMainStream().getModes());
        
        registerIOHandler(openFile.getMainStream());        
    }
    
    public static Stream handlerForFileno(Ruby runtime, int fileno) throws BadDescriptorException, IOException {
        switch (fileno) {
        case 0:
            return new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(runtime.getIn()), fileno), FileDescriptor.in);
        case 1:
            return new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(runtime.getOut()), fileno), FileDescriptor.out);
        case 2:
            return new ChannelStream(runtime, new ChannelDescriptor(Channels.newChannel(runtime.getErr()), fileno), FileDescriptor.err);
        default:
            throw new BadDescriptorException();
        }
    }
    
    private static ObjectAllocator IO_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new RubyIO(runtime, klass);
        }
    };

    public static RubyClass createIOClass(Ruby runtime) {
        RubyClass ioClass = runtime.defineClass("IO", runtime.getObject(), IO_ALLOCATOR);
        CallbackFactory callbackFactory = runtime.callbackFactory(RubyIO.class);   
        ioClass.kindOf = new RubyModule.KindOf() {
                public boolean isKindOf(IRubyObject obj, RubyModule type) {
                    return obj instanceof RubyIO;
                }
            };

        ioClass.includeModule(runtime.getEnumerable());
        
        // TODO: Implement tty? and isatty.  We have no real capability to
        // determine this from java, but if we could set tty status, then
        // we could invoke jruby differently to allow stdin to return true
        // on this.  This would allow things like cgi.rb to work properly.
        
        ioClass.defineAnnotatedMethods(RubyIO.class);

        // Constants for seek
        ioClass.fastSetConstant("SEEK_SET", runtime.newFixnum(Stream.SEEK_SET));
        ioClass.fastSetConstant("SEEK_CUR", runtime.newFixnum(Stream.SEEK_CUR));
        ioClass.fastSetConstant("SEEK_END", runtime.newFixnum(Stream.SEEK_END));
        
        ioClass.dispatcher = callbackFactory.createDispatcher(ioClass);

        return ioClass;
    }

    /*
     * See checkReadable for commentary.
     */
    protected void checkWriteable() {
        if (!isOpen() || !openFile.modes.isWritable()) {
            throw getRuntime().newIOError("not opened for writing");
        }
    }

    /*
     * What the IO object "thinks" it can do.  If two IO objects share
     * the same fileno (IOHandler), then it is possible for one to pull
     * the rug out from the other.  This will make the second object still
     * "think" that the file is open.  Secondly, if two IO objects share
     * the same fileno, but the second one only has a subset of the access
     * permissions, then it will "think" that it cannot do certain 
     * operations.
     */
    protected void checkReadable() {
        if (!isOpen() || !openFile.modes.isReadable()) {
            throw getRuntime().newIOError("not opened for reading");            
        }
    }
    
    public boolean isOpen() {
        return isOpen;
    }

    public OutputStream getOutStream() {
        return openFile.getMainStream().newOutputStream();
    }

    public InputStream getInStream() {
        return openFile.getMainStream().newInputStream();
    }

    public Channel getChannel() {
        if (openFile.getMainStream() instanceof ChannelStream) {
            return ((ChannelStream) openFile.getMainStream()).getDescriptor().getChannel();
        } else {
            return null;
        }
    }
    
    public Stream getHandler() {
        return openFile.getMainStream();
    }

    @JRubyMethod(name = "reopen", required = 1, optional = 1)
    public IRubyObject reopen(IRubyObject[] args) {
    	if (args.length < 1) {
            throw getRuntime().newArgumentError("wrong number of arguments");
    	}
    	
    	IRubyObject tmp = TypeConverter.convertToTypeWithCheck(args[0],
    	        getRuntime().getIO(), MethodIndex.getIndex("to_io"), "to_io");
    	if (!tmp.isNil()) {
            try {
                RubyIO ios = (RubyIO) tmp;

                if (ios.openFile == this.openFile) {
                    return this;
                }

                OpenFile originalFile = ios.openFile;
                OpenFile selfFile = openFile;

                long position = 0;
                if (originalFile.getModes().isReadable()) {
                    position = originalFile.getMainStream().fgetpos();
                }

                if (originalFile.getPipeStream() != null) {
                    originalFile.getPipeStream().fflush();
                } else if (originalFile.getModes().isWritable()) {
                    originalFile.getMainStream().fflush();
                }

                if (selfFile.getModes().isWritable()) {
                    if (selfFile.getPipeStream() != null) {
                        selfFile.getPipeStream().fflush();
                    } else {
                        selfFile.getMainStream().fflush();
                    }
                }

                selfFile.setModes(originalFile.getModes());
                selfFile.setPid(originalFile.getPid());
                selfFile.setLineNumber(originalFile.getLineNumber());
                selfFile.setPath(originalFile.getPath());
                selfFile.setFinalizer(originalFile.getFinalizer());

                ChannelDescriptor selfDescriptor = selfFile.getMainStream().getDescriptor();
                ChannelDescriptor originalDescriptor = originalFile.getMainStream().getDescriptor();

                // confirm we're not reopening self's channel
                if (selfDescriptor.getChannel() != originalDescriptor.getChannel()) {
                    // this is our poor-man's check for stdio; Ruby appears to save stdio streams
                    // for posterity to handle the stdio streams differently
                    if (selfDescriptor.getFileno() >=0 && selfDescriptor.getFileno() <= 2) {
                        // TODO: this should turn self's channel (backed by stdio) into target using same fileno
                        // and dup the target channel. This saves fileno, but doesn't actually dup channel
                        selfDescriptor.setChannel(originalDescriptor.getChannel());
                    } else {
                        Stream pipeFile = selfFile.getPipeStream();
                        IOModes modes = selfFile.getModes();
                        selfFile.getMainStream().fclose();
                        selfFile.setPipeStream(null);

                        // TODO: turn off readable? am I reading this right?
                        // This only seems to be used while duping below, since modes gets
                        // reset to actual modes afterward
                        //fptr->mode &= (m & FMODE_READABLE) ? ~FMODE_READABLE : ~FMODE_WRITABLE;

                        // TODO: dup the original channel into self's channel with same fileno
                        if (pipeFile != null) {
                            // TODO: new main handler is original handler opened in 'r' mode
                            //selfFile.handler = new IOHandlerNio(getRuntime(), originalFile.handler.getChannel(), originalFile.handler.getFileno(), IOModes.RDONLY);
                            selfFile.setPipeStream(pipeFile);
                        } else {
                            selfFile.setMainStream(new ChannelStream(getRuntime(), new ChannelDescriptor(originalDescriptor.getChannel(), selfDescriptor.getFileno())));
                            
                            // since we're not actually duping the incoming channel into our handler, we need to
                            // copy the original sync behavior from the other handler
                            selfFile.getMainStream().setSync(selfFile.getMainStream().isSync());
                        }
                        selfFile.setModes(modes);
                    }
                    // TODO: anything threads attached to original fd are notified of the close...
                    // see rb_thread_fd_close
                }

                // TODO: more pipe logic
    //            if (fptr->f2 && fd != fileno(fptr->f2)) {
    //                fd = fileno(fptr->f2);
    //                if (!orig->f2) {
    //                    fclose(fptr->f2);
    //                    rb_thread_fd_close(fd);
    //                    fptr->f2 = 0;
    //                }
    //                else if (fd != (fd2 = fileno(orig->f2))) {
    //                    fclose(fptr->f2);
    //                    rb_thread_fd_close(fd);
    //                    if (dup2(fd2, fd) < 0)
    //                        rb_sys_fail(orig->path);
    //                    fptr->f2 = rb_fdopen(fd, "w");
    //                }
    //            }

                // When we reopen, we want our fileno to be preserved even
                // though we have a new IOHandler.
                // Note: When we clone we get a new fileno...then we replace it.
                // This ends up incrementing our fileno index up, which makes the
                // fileno we choose different from ruby.  Since this seems a bit
                // too implementation specific, I did not bother trying to get
                // these to agree (what scary code would depend on fileno generating
                // a particular way?)
                
                // TODO: set our metaclass to target's class (i.e. scary!)

                // Update fileno list with our new handler
                registerIOHandler(selfFile.getMainStream());

                // TODO: restore binary mode
    //            if (fptr->mode & FMODE_BINMODE) {
    //                rb_io_binmode(io);
    //            }
            } catch (IOException ex) { // TODO: better error handling
                throw getRuntime().newIOError("could not reopen: " + ex.getMessage());
            } catch (BadDescriptorException ex) {
                throw getRuntime().newIOError("could not reopen: " + ex.getMessage());
            } catch (PipeException ex) {
                throw getRuntime().newIOError("could not reopen: " + ex.getMessage());
            }
        } else {
            IRubyObject pathString = args[0].convertToString();
            
            // TODO: check safe, taint on incoming string
            
            if (openFile == null) {
                openFile = new OpenFile();
                openFile.setModes(new IOModes(getRuntime()));
            }
            
            if (args.length > 1) {
                IRubyObject modeString = args[1].convertToString();
                
                openFile.setModes(new IOModes(getRuntime(), modeString.toString()));
            }
            
            String path = pathString.toString();
            openFile.setPath(path);
            
            try {
                if (openFile.getMainStream() == null) {
                    if ("/dev/null".equals(path) && System.getProperty("os.name").contains("Windows")) {
                        path = "NUL:";
                    }
                    openFile.setMainStream(ChannelStream.fopen(getRuntime(), path, openFile.getModes()));
                    isOpen = true;
                    
                    registerIOHandler(openFile.getMainStream());
                    if (openFile.getPipeStream() != null) {
                        openFile.getPipeStream().fclose();
                        unregisterIOHandler(openFile.getPipeStream().getDescriptor().getFileno());
                        openFile.setPipeStream(null);
                    }
                    return this;
                } else {
                    // Ruby code frequently uses a platform check to choose "NUL:" on windows
                    // but since that check doesn't work well on JRuby, we help it out
                    if ("/dev/null".equals(path)) {
                        if (System.getProperty("os.name").contains("Windows")) {
                            path = "NUL:";
                        }
                    }
                    
                    // TODO: This is an freopen in MRI, this is close, but not quite the same
                    openFile.getMainStream().freopen(path,openFile.getModes());

                    // re-register
                    registerIOHandler(openFile.getMainStream());

                    if (openFile.getPipeStream() != null) {
                        // TODO: pipe handler to be reopened with path and "w" mode
                    }
                }
            } catch (PipeException pe) {
                throw getRuntime().newErrnoEPIPEError();
            } catch (IOException ex) {
                throw getRuntime().newIOErrorFromException(ex);
            } catch (BadDescriptorException ex) {
                throw getRuntime().newErrnoEBADFError();
            } catch (Stream.InvalidValueException e) {
            	throw getRuntime().newErrnoEINVALError();
            }
        }
        
        // A potentially previously close IO is being 'reopened'.
        isOpen = true;
        return this;
    }
    
    private ByteList getSeparatorForGets(IRubyObject[] args) {
        IRubyObject sepVal;

        if (args.length > 0) {
            sepVal = args[0];
        } else {
            sepVal = getRuntime().getRecordSeparatorVar().get();
        }
        
        ByteList separator = sepVal.isNil() ? null : ((RubyString) sepVal).getByteList();

        if (separator != null && separator.realSize == 0) {
            separator = Stream.PARAGRAPH_DELIMETER;
        }
        
        return separator;
    }

    /** Read a line.
     * 
     */
    // TODO: Most things loop over this and always pass it the same arguments
    // meaning they are an invariant of the loop.  Think about fixing this.
    public IRubyObject internalGets(IRubyObject[] args) {
        return internalGets(getSeparatorForGets(args));
    }

    public IRubyObject internalGets(ByteList separator) {
        checkReadable();
        
        try {		
            ByteList newLine = openFile.getMainStream().fgets(separator);

            if (newLine != null) {
                openFile.setLineNumber(openFile.getLineNumber() + 1);
                getRuntime().getGlobalVariables().set("$.", getRuntime().newFixnum(openFile.getLineNumber()));
                RubyString result = RubyString.newString(getRuntime(), newLine);
                result.taint();

                return result;
            }
		    
            return getRuntime().getNil();
        } catch (EOFException e) {
            return getRuntime().getNil();
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
    }
    // IO class methods.

    @JRubyMethod(name = "initialize", required = 1, optional = 1, frame = true, visibility = Visibility.PRIVATE)
    public IRubyObject initialize(IRubyObject[] args, Block unusedBlock) {
        if (unusedBlock.isGiven()) {
            getRuntime().getWarnings().warn(ID.BLOCK_NOT_ACCEPTED,
                    "IO::new() does not take block; use IO::open() instead", "IO::new()");
        }

        int count = args.length;
        int newFileno = RubyNumeric.fix2int(args[0]);
        String mode = null;
        
        if (count > 1) {
            mode = args[1].convertToString().toString();
        }

        // See if we already have this descriptor open.
        // If so then we can mostly share the handler (keep open
        // file, but possibly change the mode).
        Stream existingIOHandler = getIOHandlerByFileno(newFileno);
        
        if (existingIOHandler == null) {
            // this seems unlikely to happen unless it's a totally bogus fileno
            // ...so do we even need to bother trying to create one?
            if (mode == null) {
                mode = "r";
            }
            
            try {
                openFile.setMainStream(handlerForFileno(getRuntime(), newFileno));
            } catch (BadDescriptorException e) {
                throw getRuntime().newErrnoEBADFError();
            } catch (IOException e) {
                throw getRuntime().newErrnoEBADFError();
            }
            //modes = new IOModes(getRuntime(), mode);
            
            registerIOHandler(openFile.getMainStream());
        } else {
            // We are creating a new IO object that shares the same
            // IOHandler (and fileno).  
            openFile.setMainStream(existingIOHandler);
            
            // Inherit if no mode specified otherwise create new one
            openFile.setModes(mode == null ? openFile.mainStream.getModes() : new IOModes(getRuntime(), mode));

            // Reset file based on modes.
            try {
                openFile.getMainStream().reset(openFile.getModes());
            } catch (PipeException pe) {
                throw getRuntime().newErrnoEPIPEError();
            } catch (BadDescriptorException bde) {
                throw getRuntime().newErrnoEBADFError();
            } catch (Stream.InvalidValueException e) {
            	throw getRuntime().newErrnoEINVALError();
            } catch (IOException e) {
                throw getRuntime().newIOError(e.getMessage());
            }
        }
        
        return this;
    }

    @JRubyMethod(name = "open", required = 1, optional = 1, frame = true, meta = true)
    public static IRubyObject open(IRubyObject recv, IRubyObject[] args, Block block) {
        Ruby runtime = recv.getRuntime();
        RubyIO io = new RubyIO(runtime, (RubyClass) recv);
        io.initialize(args, block);

        if (block.isGiven()) {
            try {
                return block.yield(runtime.getCurrentContext(), io);
            } finally {
                if (io.isOpen()) {
                    io.close();
                }
            }
        }

        return io;
    }

    // This appears to be some windows-only mode.  On a java platform this is a no-op
    @JRubyMethod(name = "binmode")
    public IRubyObject binmode() {
            return this;
    }

    @JRubyMethod(name = "syswrite", required = 1)
    public IRubyObject syswrite(IRubyObject obj) {
        try {
            if (obj instanceof RubyString) {
                return getRuntime().newFixnum(openFile.getMainStream().write(((RubyString)obj).getByteList()));
            } else {
                // FIXME: unlikely to be efficient, but probably correct
                return getRuntime().newFixnum(
                        openFile.getMainStream().write(
                        ((RubyString)obj.callMethod(
                            obj.getRuntime().getCurrentContext(), MethodIndex.TO_S, "to_s")).getByteList()));
            }
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (IOException e) {
            throw getRuntime().newSystemCallError(e.getMessage());
        }
    }
    
    /** io_write
     * 
     */
    @JRubyMethod(name = "write", required = 1)
    public IRubyObject write(IRubyObject obj) {
        try {
            ByteList objBytes = obj.asString().getByteList();
            if (objBytes.length() == 0) {
                // nothing to write, just return
                return RubyFixnum.zero(getRuntime());
            } else {
                checkWriteable();
                return getRuntime().newFixnum(openFile.getMainStream().fwrite(objBytes));
            }
        } catch (Stream.BadDescriptorException e) {
            return RubyFixnum.zero(getRuntime());
        } catch (IOException e) {
            String message = e.getMessage();
            if(message != null) {
                if(message.equals("Broken pipe")) {
                    throw getRuntime().newErrnoEPIPEError();
                } else if(message.equals("not opened for writing")) {
                    throw getRuntime().newIOError(message);
                }
            }

            if(getRuntime().getDebug().isTrue()) {
                getRuntime().getWarnings().warn(ID.SWALLOWED_IO_EXCEPTION, "swallowed IO exception: " + e.toString());
                e.printStackTrace();
            }

            return RubyFixnum.zero(getRuntime());
        }
    }

    /** rb_io_addstr
     * 
     */
    @JRubyMethod(name = "<<", required = 1)
    public IRubyObject op_concat(IRubyObject anObject) {
        // Claims conversion is done via 'to_s' in docs.
        IRubyObject strObject = anObject.callMethod(getRuntime().getCurrentContext(), MethodIndex.TO_S, "to_s");

        write(strObject);
        
        return this; 
    }

    @JRubyMethod(name = "fileno", alias = "to_i")
    public RubyFixnum fileno() {
        return getRuntime().newFixnum(openFile.getMainStream().getDescriptor().getFileno());
    }
    
    /** Returns the current line number.
     * 
     * @return the current line number.
     */
    @JRubyMethod(name = "lineno")
    public RubyFixnum lineno() {
        return getRuntime().newFixnum(openFile.getLineNumber());
    }

    /** Sets the current line number.
     * 
     * @param newLineNumber The new line number.
     */
    @JRubyMethod(name = "lineno=", required = 1)
    public RubyFixnum lineno_set(IRubyObject newLineNumber) {
        openFile.setLineNumber(RubyNumeric.fix2int(newLineNumber));

        return (RubyFixnum) newLineNumber;
    }

    /** Returns the current sync mode.
     * 
     * @return the current sync mode.
     */
    @JRubyMethod(name = "sync")
    public RubyBoolean sync() {
        return getRuntime().newBoolean(openFile.getMainStream().isSync());
    }
    
    /**
     * <p>Return the process id (pid) of the process this IO object
     * spawned.  If no process exists (popen was not called), then
     * nil is returned.  This is not how it appears to be defined
     * but ruby 1.8 works this way.</p>
     * 
     * @return the pid or nil
     */
    @JRubyMethod(name = "pid")
    public IRubyObject pid() {
        int pid = openFile.getPid();
        
        return pid == -1 ? getRuntime().getNil() : getRuntime().newFixnum(pid); 
    }
    
    public boolean hasPendingBuffered() {
        return openFile.getMainStream().hasPendingBuffered();
    }
    
    @JRubyMethod(name = {"pos", "tell"})
    public RubyFixnum pos() {
        try {
            return getRuntime().newFixnum(openFile.getMainStream().fgetpos());
        } catch (BadDescriptorException bde) {
            throw getRuntime().newErrnoEBADFError();
        } catch (Stream.PipeException e) {
            throw getRuntime().newErrnoESPIPEError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
    }
    
    @JRubyMethod(name = "pos=", required = 1)
    public RubyFixnum pos_set(IRubyObject newPosition) {
        long offset = RubyNumeric.num2long(newPosition);

        if (offset < 0) {
            throw getRuntime().newSystemCallError("Negative seek offset");
        }
        
        try {
            openFile.getMainStream().fseek(offset, Stream.SEEK_SET);
        } catch (BadDescriptorException bde) {
            throw getRuntime().newErrnoEBADFError();
        } catch (Stream.InvalidValueException e) {
            throw getRuntime().newErrnoEINVALError();
        } catch (Stream.PipeException e) {
            throw getRuntime().newErrnoESPIPEError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
        
        return (RubyFixnum) newPosition;
    }
    
    /** Print some objects to the stream.
     * 
     */
    @JRubyMethod(name = "print", rest = true)
    public IRubyObject print(IRubyObject[] args) {
        if (args.length == 0) {
            args = new IRubyObject[] { getRuntime().getCurrentContext().getCurrentFrame().getLastLine() };
        }

        IRubyObject fs = getRuntime().getGlobalVariables().get("$,");
        IRubyObject rs = getRuntime().getGlobalVariables().get("$\\");
        ThreadContext context = getRuntime().getCurrentContext();
        
        for (int i = 0; i < args.length; i++) {
            if (i > 0 && !fs.isNil()) {
                callMethod(context, "write", fs);
            }
            if (args[i].isNil()) {
                callMethod(context, "write", getRuntime().newString("nil"));
            } else {
                callMethod(context, "write", args[i]);
            }
        }
        if (!rs.isNil()) {
            callMethod(context, "write", rs);
        }

        return getRuntime().getNil();
    }

    @JRubyMethod(name = "printf", required = 1, rest = true)
    public IRubyObject printf(IRubyObject[] args) {
        callMethod(getRuntime().getCurrentContext(), "write", RubyKernel.sprintf(this, args));
        return getRuntime().getNil();
    }
    
    @JRubyMethod(name = "putc", required = 1)
    public IRubyObject putc(IRubyObject object) {
        int c;
        
        if (getRuntime().getString().isInstance(object)) {
            String value = ((RubyString) object).toString();
            
            if (value.length() > 0) {
                c = value.charAt(0);
            } else {
                throw getRuntime().newTypeError("Cannot convert String to Integer");
            }
        } else if (getRuntime().getFixnum().isInstance(object)){
            c = RubyNumeric.fix2int(object);
        } else { // What case will this work for?
            c = RubyNumeric.fix2int(object.callMethod(getRuntime().getCurrentContext(), MethodIndex.TO_I, "to_i"));
        }

        try {
            openFile.getMainStream().fputc(c);
        } catch (Stream.BadDescriptorException e) {
            return RubyFixnum.zero(getRuntime());
        } catch (IOException e) {
            return RubyFixnum.zero(getRuntime());
        }
        
        return object;
    }
    
    // This was a getOpt with one mandatory arg, but it did not work
    // so I am parsing it for now.
    @JRubyMethod(name = "seek", required = 1, optional = 1)
    public RubyFixnum seek(IRubyObject[] args) {
        if (args.length == 0) {
            throw getRuntime().newArgumentError("wrong number of arguments");
        }
        
        long offset = RubyNumeric.num2long(args[0]);
        int type = Stream.SEEK_SET;
        
        if (args.length > 1) {
            type = RubyNumeric.fix2int(args[1].convertToInteger());
        }
        
        try {
            openFile.getMainStream().fseek(offset, type);
        } catch (BadDescriptorException bde) {
            throw getRuntime().newErrnoEBADFError();
        } catch (Stream.InvalidValueException e) {
        	throw getRuntime().newErrnoEINVALError();
        } catch (Stream.PipeException e) {
        	throw getRuntime().newErrnoESPIPEError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
        
        return RubyFixnum.zero(getRuntime());
    }

    @JRubyMethod(name = "rewind")
    public RubyFixnum rewind() {
        try {
            openFile.getMainStream().rewind();
        } catch (BadDescriptorException bde) {
            throw getRuntime().newErrnoEBADFError();
        } catch (Stream.InvalidValueException e) {
            throw getRuntime().newErrnoEINVALError();
        } catch (Stream.PipeException e) {
            throw getRuntime().newErrnoESPIPEError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }

        // Must be back on first line on rewind.
        openFile.setLineNumber(0);
        
        return RubyFixnum.zero(getRuntime());
    }
    
    @JRubyMethod(name = "fsync")
    public RubyFixnum fsync() {
        checkWriteable();

        try {
            openFile.getMainStream().sync();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        }

        return RubyFixnum.zero(getRuntime());
    }

    /** Sets the current sync mode.
     * 
     * @param newSync The new sync mode.
     */
    @JRubyMethod(name = "sync=", required = 1)
    public IRubyObject sync_set(IRubyObject newSync) {
        openFile.getMainStream().setSync(newSync.isTrue());

        return this;
    }

    @JRubyMethod(name = {"eof?", "eof"})
    public RubyBoolean eof_p() {
        try {
            boolean isEOF = openFile.getMainStream().feof(); 
            return isEOF ? getRuntime().getTrue() : getRuntime().getFalse();
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
    }

    @JRubyMethod(name = {"tty?", "isatty"})
    public RubyBoolean tty_p() {
        return getRuntime().newBoolean(getRuntime().getPosix().isatty(openFile.getMainStream().getFD()));
    }
    
    @JRubyMethod(name = "initialize_copy", required = 1)
    public IRubyObject initialize_copy(IRubyObject original){
        if (this == original) return this;

        RubyIO originalIO = (RubyIO) original;
        
        OpenFile originalFile = originalIO.openFile;
        OpenFile newFile = openFile;
        
        try {
            if (originalFile.getPipeStream() != null) {
                originalFile.getPipeStream().fflush();
                originalFile.getMainStream().fseek(0, Stream.SEEK_CUR);
            } else if (originalFile.getModes().isWritable()) {
                originalFile.getMainStream().fflush();
            } else {
                originalFile.getMainStream().fseek(0, Stream.SEEK_CUR);
            }

            newFile.setModes(originalFile.getModes());
            newFile.setPid(originalFile.getPid());
            newFile.setLineNumber(originalFile.getLineNumber());
            newFile.setPath(originalFile.getPath());

            String fdMode = null;
            if (newFile.getModes().isReadable()) {
                if (newFile.getModes().isWritable()) {
                    if (originalFile.getPipeStream() != null) {
                        fdMode = "r";
                    } else {
                        fdMode = "r+";
                    }
                } else {
                    fdMode = "r";
                }
            } else if (newFile.getModes().isWritable()) {
                fdMode = "w";
            } else {
                fdMode = "r";
            }

            // FIXME: use fdMode for the cloned handler's channel (which is sortof our fd)

            // Two pos pointers?  
            // http://blade.nagaokaut.ac.jp/ruby/ruby-talk/81513
            // So if I understand this correctly, the descriptor level stuff
            // shares things like position, but the higher level stuff uses
            // a different set of libc functions (FILE*), which does not share
            // position.  Our current implementation implements our higher 
            // level operations on top of our 'sys' versions.  So we could in
            // fact share everything.  Unfortunately, we want to clone ruby's
            // behavior (i.e. show how this interface bleeds their 
            // implementation). So our best bet, is to just create a yet another
            // copy of the handler.  In fact, ruby 1.8 must do this as the cloned
            // resource is in fact a different fileno.  What is clone for again?        

            // FIXME: Missing step here is to clone the channel, which we can't do at present.
            // Ruby dup's the original file descriptor and then opens it into a new FILE structure
            // This would be roughly equivalent to us duping the original channel and inserting it
            // into a new IOHandler. We may be able to get away with just preserving the shared
            // channel (keeping it open until all referencing handlers have closed it) since
            // at least one platform appears to share position between the two
            
            openFile.setMainStream(new ChannelStream(getRuntime(), new ChannelDescriptor(originalFile.getMainStream().getDescriptor().getChannel(), getNewFileno()), openFile.getModes()));
            registerIOHandler(openFile.getMainStream());
        } catch (IOException ex) {
            throw getRuntime().newIOError("could not init copy: " + ex);
        } catch (BadDescriptorException ex) {
            throw getRuntime().newIOError("could not init copy: " + ex);
        } catch (PipeException ex) {
            throw getRuntime().newIOError("could not init copy: " + ex);
        } catch (InvalidValueException ex) {
            throw getRuntime().newIOError("could not init copy: " + ex);
        }
        
        return this;
    }
    
    /** Closes the IO.
     * 
     * @return The IO.
     */
    @JRubyMethod(name = "closed?")
    public RubyBoolean closed_p() {
        return isOpen() ? getRuntime().getFalse() : getRuntime().getTrue();
    }

    /** 
     * <p>Closes all open resources for the IO.  It also removes
     * it from our magical all open file descriptor pool.</p>
     * 
     * @return The IO.
     */
    @JRubyMethod(name = "close")
    public IRubyObject close() {
        isOpen = false;
        
        try {
            openFile.getMainStream().fclose();
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
        
        unregisterIOHandler(openFile.getMainStream().getDescriptor().getFileno());
        
        return this;
    }

    @JRubyMethod(name = "close_write")
    public IRubyObject close_write() {
        try {
            openFile.getMainStream().closeWrite();
        } catch (BadDescriptorException bde) {
            throw getRuntime().newErrnoEBADFError();
        } catch (IOException ioe) {
            // hmmmm
        }
        return this;
    }

    /** Flushes the IO output stream.
     * 
     * @return The IO.
     */
    @JRubyMethod(name = "flush")
    public RubyIO flush() {
        try { 
            openFile.getMainStream().fflush();
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }

        return this;
    }

    /** Read a line.
     * 
     */
    @JRubyMethod(name = "gets", optional = 1)
    public IRubyObject gets(IRubyObject[] args) {
        IRubyObject result = internalGets(args);

        if (!result.isNil()) getRuntime().getCurrentContext().getCurrentFrame().setLastLine(result);

        return result;
    }

    public boolean getBlocking() {
        return ((ChannelStream) openFile.getMainStream()).isBlocking();
     }

    @JRubyMethod(name = "fcntl", required = 2)
    public IRubyObject fcntl(IRubyObject cmd, IRubyObject arg) throws IOException {
        long realCmd = cmd.convertToInteger().getLongValue();
        
        // FIXME: Arg may also be true, false, and nil and still be valid.  Strangely enough, 
        // protocol conversion is not happening in Ruby on this arg?
        if (!(arg instanceof RubyNumeric)) return getRuntime().newFixnum(0);
        
        long realArg = ((RubyNumeric)arg).getLongValue();

        // Fixme: Only F_SETFL is current supported
        if (realCmd == 1L) {  // cmd is F_SETFL
            boolean block = true;
            
            if ((realArg & IOModes.NONBLOCK) == IOModes.NONBLOCK) {
                block = false;
            }

            try {
                openFile.getMainStream().setBlocking(block);
            } catch (IOException e) {
                throw getRuntime().newIOError(e.getMessage());
            }
        }
        
        return getRuntime().newFixnum(0);
    }

    @JRubyMethod(name = "puts", rest = true)
    public IRubyObject puts(IRubyObject[] args) {
    	ThreadContext context = getRuntime().getCurrentContext();
        
        if (args.length == 0) {
            callMethod(context, "write", getRuntime().newString("\n"));
            return getRuntime().getNil();
        }

        for (int i = 0; i < args.length; i++) {
            String line;
            
            if (args[i].isNil()) {
                line = "nil";
            } else if (getRuntime().isInspecting(args[i])) {
                line = "[...]";
            } else if (args[i] instanceof RubyArray) {
                inspectPuts((RubyArray) args[i]);
                continue;
            } else {
                line = args[i].toString();
            }
            
            callMethod(context, "write", getRuntime().newString(line));
            
            if (!line.endsWith("\n")) {
                callMethod(context, "write", getRuntime().newString("\n"));
            }
        }
        return getRuntime().getNil();
    }
    
    private IRubyObject inspectPuts(RubyArray array) {
        try {
            getRuntime().registerInspecting(array);
            return puts(array.toJavaArray());
        } finally {
            getRuntime().unregisterInspecting(array);
        }
    }

    /** Read a line.
     * 
     */
    @JRubyMethod(name = "readline", optional = 1)
    public IRubyObject readline(IRubyObject[] args) {
        IRubyObject line = gets(args);

        if (line.isNil()) {
            throw getRuntime().newEOFError();
        }
        
        return line;
    }

    /** Read a byte. On EOF returns nil.
     * 
     */
    @JRubyMethod(name = "getc")
    public IRubyObject getc() {
        checkReadable();
        
        try {
            int c = openFile.getMainStream().fgetc();
        
            return c == -1 ? getRuntime().getNil() : getRuntime().newFixnum(c);
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (EOFException e) {
            throw getRuntime().newEOFError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
    }
    
    /** 
     * <p>Pushes char represented by int back onto IOS.</p>
     * 
     * @param number to push back
     */
    @JRubyMethod(name = "ungetc", required = 1)
    public IRubyObject ungetc(IRubyObject number) {
        openFile.getMainStream().ungetc(RubyNumeric.fix2int(number));

        return getRuntime().getNil();
    }
    
    @JRubyMethod(name = "readpartial", required = 1, optional = 1)
    public IRubyObject readpartial(IRubyObject[] args) {
        if(!(openFile.mainStream instanceof ChannelStream)) {
            // cryptic for the uninitiated...
            throw getRuntime().newNotImplementedError("readpartial only works with Nio based handlers");
        }
    	try {
            ByteList buf = ((ChannelStream)openFile.getMainStream()).readpartial(RubyNumeric.fix2int(args[0]));
            IRubyObject strbuf = RubyString.newString(getRuntime(), buf == null ? new ByteList(ByteList.NULL_ARRAY) : buf);
            if(args.length > 1) {
                args[1].callMethod(getRuntime().getCurrentContext(),MethodIndex.OP_LSHIFT, "<<", strbuf);
                return args[1];
            } 

            return strbuf;
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (EOFException e) {
            return getRuntime().getNil();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
    }

    // FIXME: according to MRI's RI, sysread only takes one arg
    @JRubyMethod(name = "sysread", required = 1, optional = 1)
    public IRubyObject sysread(IRubyObject[] args) {
        int len = (int)RubyNumeric.num2long(args[0]);
        if (len < 0) throw getRuntime().newArgumentError("Negative size");

        try {
            RubyString str;
            if (args.length == 1 || args[1].isNil()) {
                if (len == 0) return RubyString.newString(getRuntime(), "");
                str = RubyString.newString(getRuntime(), openFile.getMainStream().read(len));
            } else {
                str = args[1].convertToString();
                if (len == 0) {
                    str.setValue(new ByteList());
                    return str;
                }
                str.setValue(openFile.getMainStream().read(len)); // should preserve same instance
            }
            str.setTaint(true);
            return str;
            
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newIOError("bad descriptor");
        } catch (EOFException e) {
            throw getRuntime().newEOFError();
    	} catch (IOException e) {
            // All errors to sysread should be SystemCallErrors, but on a closed stream
            // Ruby returns an IOError.  Java throws same exception for all errors so
            // we resort to this hack...
            if ("File not open".equals(e.getMessage())) {
                    throw getRuntime().newIOError(e.getMessage());
            }
    	    throw getRuntime().newSystemCallError(e.getMessage());
    	}
    }
    
    @JRubyMethod(name = "read", rest = true)
    public IRubyObject read(IRubyObject[] args) {
        checkReadable();

        int argCount = args.length;
        RubyString callerBuffer = null;
        boolean readEntireStream = (argCount == 0 || args[0].isNil());

        try {
            // Reads when already at EOF keep us at EOF
            // We do retain the possibility of un-EOFing if the handler
            // gets new data
            if (atEOF && openFile.getMainStream().feof()) throw new EOFException();

            if (argCount == 2) {
                callerBuffer = !args[1].isNil() ? args[1].convertToString() : getRuntime().newString(); 
            }

            ByteList buf;
            if (readEntireStream) {
                buf = openFile.getMainStream().readall();
            } else {
                long len = RubyNumeric.num2long(args[0]);
                if (len < 0) throw getRuntime().newArgumentError("negative length " + len + " given");
                if (len == 0) return getRuntime().newString("");
                buf = openFile.getMainStream().fread((int)len);
            }

            if (buf == null) throw new EOFException();

            // If we get here then no EOFException was thrown in the handler.  We
            // might still need to set our atEOF flag back to true depending on
            // whether we were reading the entire stream (see the finally block below)
            atEOF = false;
            if (callerBuffer != null) {
                callerBuffer.setValue(buf);
                return callerBuffer;
            }
            
            return RubyString.newString(getRuntime(), buf);
        } catch (Stream.BadDescriptorException e) {
            e.printStackTrace();
            throw getRuntime().newErrnoEBADFError();
        } catch (EOFException e) {
            // on EOF, IO#read():
            // with no args or a nil first arg will return an empty string
            // with a non-nil first arg will return nil
            atEOF = true;
            if (callerBuffer != null) {
                callerBuffer.setValue("");
                return readEntireStream ? callerBuffer : getRuntime().getNil();
            }

            return readEntireStream ? getRuntime().newString("") : getRuntime().getNil();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        } finally {
            // reading the entire stream always puts us at EOF
            if (readEntireStream) {
                atEOF = true;
            }
        }
    }

    /** Read a byte. On EOF throw EOFError.
     * 
     */
    @JRubyMethod(name = "readchar")
    public IRubyObject readchar() {
        checkReadable();
        
        try {
            int c = openFile.getMainStream().fgetc();
        
            if (c == -1) throw getRuntime().newEOFError();
        
            return getRuntime().newFixnum(c);
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (EOFException e) {
            throw getRuntime().newEOFError();
        } catch (IOException e) {
            throw getRuntime().newIOError(e.getMessage());
        }
    }
    
    @JRubyMethod
    public IRubyObject stat() {
        return getRuntime().newFileStat(openFile.getMainStream().getFD());
    }

    /** 
     * <p>Invoke a block for each byte.</p>
     */
    @JRubyMethod(name = "each_byte", frame = true)
    public IRubyObject each_byte(Block block) {
    	try {
            ThreadContext context = getRuntime().getCurrentContext();
            for (int c = openFile.getMainStream().fgetc(); c != -1; c = openFile.getMainStream().fgetc()) {
                assert c < 256;
                block.yield(context, getRuntime().newFixnum(c));
            }

            return getRuntime().getNil();
        } catch (Stream.BadDescriptorException e) {
            throw getRuntime().newErrnoEBADFError();
        } catch (EOFException e) {
            return getRuntime().getNil();
    	} catch (IOException e) {
    	    throw getRuntime().newIOError(e.getMessage());
        }
    }

    /** 
     * <p>Invoke a block for each line.</p>
     */
    @JRubyMethod(name = {"each_line", "each"}, optional = 1, frame = true)
    public RubyIO each_line(IRubyObject[] args, Block block) {
        ThreadContext context = getRuntime().getCurrentContext(); 
        ByteList separator = getSeparatorForGets(args);
        
        for (IRubyObject line = internalGets(separator); !line.isNil(); 
        	line = internalGets(separator)) {
            block.yield(context, line);
        }
        
        return this;
    }


    @JRubyMethod(name = "readlines", optional = 1)
    public RubyArray readlines(IRubyObject[] args) {
        ByteList separator;
        if (args.length > 0) {
            if (!getRuntime().getNilClass().isInstance(args[0]) &&
                !getRuntime().getString().isInstance(args[0])) {
                throw getRuntime().newTypeError(args[0], 
                        getRuntime().getString());
            } 
            separator = getSeparatorForGets(new IRubyObject[] { args[0] });
        } else {
            separator = getSeparatorForGets(IRubyObject.NULL_ARRAY);
        }

        RubyArray result = getRuntime().newArray();
        IRubyObject line;
        while (! (line = internalGets(separator)).isNil()) {
            result.append(line);
        }
        return result;
    }
    
    @JRubyMethod(name = "to_io")
    public RubyIO to_io() {
    	return this;
    }

    public String toString() {
        return "RubyIO(" + openFile.getModes() + ", " + openFile.getMainStream().getDescriptor().getFileno() + ")";
    }
    
    /* class methods for IO */
    
    /** rb_io_s_foreach
    *
    */
    @JRubyMethod(name = "foreach", required = 1, rest = true, frame = true, meta = true)
    public static IRubyObject foreach(IRubyObject recv, IRubyObject[] args, Block block) {
        Ruby runtime = recv.getRuntime();
        int count = args.length;
        IRubyObject filename = args[0].convertToString();
        runtime.checkSafeString(filename);
        RubyIO io = (RubyIO) RubyFile.open(recv, new IRubyObject[] { filename }, false, block);
       
        if (!io.isNil() && io.isOpen()) {
            try {
                IRubyObject[] newArgs = new IRubyObject[count - 1];
                System.arraycopy(args, 1, newArgs, 0, count - 1);
               
                IRubyObject nextLine = io.internalGets(newArgs);
                while (!nextLine.isNil()) {
                    block.yield(runtime.getCurrentContext(), nextLine);
                    nextLine = io.internalGets(newArgs);
                }
            } finally {
                io.close();
            }
        }
       
        return runtime.getNil();
    }
   
    private static RubyIO registerSelect(Selector selector, IRubyObject obj, int ops) throws IOException {
       RubyIO ioObj;
       
       if (!(obj instanceof RubyIO)) {
           // invoke to_io
           if (!obj.respondsTo("to_io")) return null;

           ioObj = (RubyIO) obj.callMethod(obj.getRuntime().getCurrentContext(), "to_io");
       } else {
           ioObj = (RubyIO) obj;
       }
       
       Channel channel = ioObj.getChannel();
       if (channel == null || !(channel instanceof SelectableChannel)) {
           return null;
       }
       
       ((SelectableChannel) channel).configureBlocking(false);
       int real_ops = ((SelectableChannel) channel).validOps() & ops;
       SelectionKey key = ((SelectableChannel) channel).keyFor(selector);
       
       if (key == null) {
           ((SelectableChannel) channel).register(selector, real_ops, obj);
       } else {
           key.interestOps(key.interestOps()|real_ops);
       }
       
       return ioObj;
   }
   
    @JRubyMethod(name = "select", required = 1, optional = 3, meta = true)
    public static IRubyObject select(IRubyObject recv, IRubyObject[] args) {
        return select_static(recv.getRuntime(), args);
    }
   
    public static IRubyObject select_static(Ruby runtime, IRubyObject[] args) {
       try {
           boolean atLeastOneDescriptor = false;
           
           Set pending = new HashSet();
           Selector selector = Selector.open();
           if (!args[0].isNil()) {
               atLeastOneDescriptor = true;
               
               // read
               for (Iterator i = ((RubyArray) args[0]).getList().iterator(); i.hasNext(); ) {
                   IRubyObject obj = (IRubyObject) i.next();
                   RubyIO ioObj = registerSelect(selector, obj, 
                           SelectionKey.OP_READ | SelectionKey.OP_ACCEPT);
                   
                   if (ioObj!=null && ioObj.hasPendingBuffered()) pending.add(obj);
               }
           }
           if (args.length > 1 && !args[1].isNil()) {
               atLeastOneDescriptor = true;
               // write
               for (Iterator i = ((RubyArray) args[1]).getList().iterator(); i.hasNext(); ) {
                   IRubyObject obj = (IRubyObject) i.next();
                   registerSelect(selector, obj, SelectionKey.OP_WRITE);
               }
           }
           if (args.length > 2 && !args[2].isNil()) {
               atLeastOneDescriptor = true;
               // Java's select doesn't do anything about this, so we leave it be.
           }
           
           long timeout = 0;
           if(args.length > 3 && !args[3].isNil()) {
               if (args[3] instanceof RubyFloat) {
                   timeout = Math.round(((RubyFloat) args[3]).getDoubleValue() * 1000);
               } else {
                   timeout = Math.round(((RubyFixnum) args[3]).getDoubleValue() * 1000);
               }
               
               if (timeout < 0) {
                   throw runtime.newArgumentError("negative timeout given");
               }
           }
           
           if (!atLeastOneDescriptor) {
               return runtime.getNil();
           }
           
           if (pending.isEmpty()) {
               if (args.length > 3) {
                   if (timeout==0) {
                       selector.selectNow();
                   } else {
                       selector.select(timeout);                       
                   }
               } else {
                   selector.select();
               }
           } else {
               selector.selectNow();               
           }
           
           List r = new ArrayList();
           List w = new ArrayList();
           List e = new ArrayList();
           for (Iterator i = selector.selectedKeys().iterator(); i.hasNext(); ) {
               SelectionKey key = (SelectionKey) i.next();
               if ((key.interestOps() & key.readyOps()
                       & (SelectionKey.OP_READ|SelectionKey.OP_ACCEPT|SelectionKey.OP_CONNECT)) != 0) {
                   r.add(key.attachment());
                   pending.remove(key.attachment());
               }
               if ((key.interestOps() & key.readyOps() & (SelectionKey.OP_WRITE)) != 0) {
                   w.add(key.attachment());
               }
           }
           r.addAll(pending);
           
           // make all sockets blocking as configured again
           for (Iterator i = selector.keys().iterator(); i.hasNext(); ) {
               SelectionKey key = (SelectionKey) i.next();
               SelectableChannel channel = key.channel();
               synchronized(channel.blockingLock()) {
                   boolean blocking = ((RubyIO) key.attachment()).getBlocking();
                   key.cancel();
                   channel.configureBlocking(blocking);
               }
           }
           selector.close();
           
           if (r.size() == 0 && w.size() == 0 && e.size() == 0) {
               return runtime.getNil();
           }
           
           List ret = new ArrayList();
           
           ret.add(RubyArray.newArray(runtime, r));
           ret.add(RubyArray.newArray(runtime, w));
           ret.add(RubyArray.newArray(runtime, e));
           
           return RubyArray.newArray(runtime, ret);
       } catch(IOException e) {
           throw runtime.newIOError(e.getMessage());
       }
   }
   
    @JRubyMethod(name = "read", required = 1, optional = 2, meta = true)
    public static IRubyObject read(IRubyObject recv, IRubyObject[] args, Block block) {
       IRubyObject[] fileArguments = new IRubyObject[] {args[0]};
       RubyIO file = (RubyIO) RubyKernel.open(recv, fileArguments, block);
       IRubyObject[] readArguments;
       
       if (args.length >= 2) {
           readArguments = new IRubyObject[] {args[1].convertToInteger()};
       } else {
           readArguments = new IRubyObject[] {};
       }
       
       try {
           
           if (args.length == 3) {
               file.seek(new IRubyObject[] {args[2].convertToInteger()});
           }
           
           return file.read(readArguments);
       } finally {
           file.close();
       }
   }
   
    @JRubyMethod(name = "readlines", required = 1, optional = 1, meta = true)
    public static RubyArray readlines(IRubyObject recv, IRubyObject[] args, Block block) {
       int count = args.length;
       
       IRubyObject[] fileArguments = new IRubyObject[] {args[0]};
       IRubyObject[] separatorArguments = count >= 2 ? new IRubyObject[]{args[1]} : IRubyObject.NULL_ARRAY;
       RubyIO file = (RubyIO) RubyKernel.open(recv, fileArguments, block);
       try {
           return file.readlines(separatorArguments);
       } finally {
           file.close();
       }
   }
   
    //XXX Hacked incomplete popen implementation to make
    @JRubyMethod(name = "popen", required = 1, optional = 1, meta = true)
    public static IRubyObject popen(IRubyObject recv, IRubyObject[] args, Block block) {
       Ruby runtime = recv.getRuntime();
       IRubyObject cmdObj = args[0].convertToString();
       runtime.checkSafeString(cmdObj);
       
       try {
           Process process = new ShellLauncher(runtime).run(cmdObj);            
           RubyIO io = new RubyIO(runtime, process);
           
           if (block.isGiven()) {
               try {
                   return block.yield(runtime.getCurrentContext(), io);
               } finally {
                   if (io.isOpen()) {
                       io.close();
                   }
                   runtime.getGlobalVariables().set("$?",  RubyProcess.RubyStatus.newProcessStatus(runtime, (process.waitFor() * 256)));
               }
           }
           return io;
       } catch (IOException e) {
           throw runtime.newIOErrorFromException(e);
       } catch (InterruptedException e) {
           throw runtime.newThreadError("unexpected interrupt");
       }
   }
   
    // NIO based pipe
    @JRubyMethod(name = "pipe", meta = true)
    public static IRubyObject pipe(IRubyObject recv) throws Exception {
       Ruby runtime = recv.getRuntime();
       Pipe pipe = Pipe.open();
       return runtime.newArrayNoCopy(new IRubyObject[]{
           new RubyIO(runtime, pipe.source()),
           new RubyIO(runtime, pipe.sink())
       });
   }
   
    /**
     * returns non-nil if input available without blocking, false if EOF or not open/readable, otherwise nil.
     */
    public IRubyObject ready() {
       try {
           if (!openFile.mainStream.isOpen() || !openFile.mainStream.isReadable() || openFile.getMainStream().feof()) {
               return getRuntime().getFalse();
           }

           int avail = openFile.getMainStream().ready();
           if (avail > 0) {
               return getRuntime().newFixnum(avail);
           } 
       } catch (Exception anyEx) {
           return getRuntime().getFalse();
       }
       return getRuntime().getNil();
   }
   
    /**
     * waits until input available or timed out and returns self, or nil when EOF reached.
     */
    public IRubyObject io_wait() {
       try {
           if (openFile.getMainStream().feof()) {
               return getRuntime().getNil();
           }
            openFile.getMainStream().waitUntilReady();
       } catch (Exception anyEx) {
           return getRuntime().getNil();
       }
       return this;
   }
}
