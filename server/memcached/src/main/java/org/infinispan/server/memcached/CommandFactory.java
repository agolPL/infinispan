/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2009, Red Hat, Inc. and/or its affiliates, and
 * individual contributors as indicated by the @author tags. See the
 * copyright.txt file in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.server.memcached;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.infinispan.Cache;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * CommandFactory.
 * 
 * @author Galder Zamarreño
 * @since 4.0
 */
public class CommandFactory {
   private static final Log log = LogFactory.getLog(CommandFactory.class);

   private final Cache cache;
   
   public CommandFactory(Cache cache) {
      this.cache = cache;
   }

   public Command createCommand(String line) throws IOException {
      if (log.isTraceEnabled()) log.trace("Command line: " + line);
      String[] args = line.trim().split(" +");

      CommandType type = null;
      String tmp = args[0];
      if(tmp == null) 
         throw new EOFException();
      else
         type = CommandType.parseType(tmp);

      switch(type) {
         case SET:
         case ADD:
         case REPLACE:
         case APPEND:
         case PREPEND:
         case CAS:
            String key = args[1]; // key
            if(key == null) throw new EOFException();

            tmp = args[2]; // flags
            if(tmp == null) throw new EOFException();
            int flags = Integer.parseInt(tmp);

            tmp = args[3]; // expiry time
            if(tmp == null) throw new EOFException();
            long expiry = Long.parseLong(tmp); // seconds

            tmp = args[4]; // number of bytes
            if(tmp == null) throw new EOFException();
            int bytes = Integer.parseInt(tmp);

//            if (type == CommandType.CAS) {
//               tmp = args[5]; // cas unique, 64-bit integer
//               long unique = Long.parseLong(tmp);
//               return type.buildCasCommand(key, flags, expiry, bytes, unique); 
//            }

            return StorageCommand.newStorageCommand(cache, type, new StorageParameters(key, flags, expiry, bytes), null);
         case GET:
         case GETS:
            List<String> keys = new ArrayList<String>(5);
            keys.addAll(Arrays.asList(args).subList(1, args.length));
            return RetrievalCommand.newRetrievalCommand(cache, type, new RetrievalParameters(keys));
         default:
            return null;
      }
   }

}
