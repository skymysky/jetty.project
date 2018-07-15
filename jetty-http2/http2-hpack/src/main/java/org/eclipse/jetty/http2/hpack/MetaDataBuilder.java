//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//


package org.eclipse.jetty.http2.hpack;


import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackException.SessionException;

public class MetaDataBuilder
{
    private final int _maxSize;
    private int _size;
    private int _status=-1;
    private String _method;
    private HttpScheme _scheme;
    private HostPortHttpField _authority;
    private String _path;
    private long _contentLength=Long.MIN_VALUE;
    private HttpFields _fields = new HttpFields(10);
    private HpackException.StreamException _streamException;
    private boolean _request;
    private boolean _response;

    /**
     * @param maxHeadersSize The maximum size of the headers, expressed as total name and value characters.
     */
    MetaDataBuilder(int maxHeadersSize)
    {
        _maxSize=maxHeadersSize;
    }

    /** Get the maxSize.
     * @return the maxSize
     */
    public int getMaxSize()
    {
        return _maxSize;
    }

    /** Get the size.
     * @return the current size in bytes
     */
    public int getSize()
    {
        return _size;
    }

    public void emit(HttpField field) throws HpackException.SessionException
    {
        HttpHeader header = field.getHeader();
        String name = field.getName();
        String value = field.getValue();
        int field_size = name.length() + (value == null ? 0 : value.length());
        _size+=field_size+32;
        if (_size>_maxSize)
            throw new HpackException.SessionException("Header Size %d > %d",_size,_maxSize);

        if (field instanceof StaticTableHttpField)
        {
            StaticTableHttpField staticField = (StaticTableHttpField)field;
            switch(header)
            {
                case C_STATUS:
                    if(checkHeader(header, _status))
                        _status = (Integer)staticField.getStaticValue();
                    _response = true;
                    break;

                case C_METHOD:
                    if(checkPseudoHeader(header, _method))
                        _method = value;
                    _request = true;
                    break;

                case C_SCHEME:
                    if(checkPseudoHeader(header, _scheme))
                        _scheme = (HttpScheme)staticField.getStaticValue();
                    _request = true;
                    break;

                default:
                    throw new IllegalArgumentException(name);
            }
        }
        else if (header!=null)
        {
            switch(header)
            {
                case C_STATUS:
                    if(checkHeader(header, _status))
                        _status = field.getIntValue();
                    _response = true;
                    break;

                case C_METHOD:
                    if(checkPseudoHeader(header, _method))
                       _method = value;
                    _request = true;
                    break;

                case C_SCHEME:
                    if(checkPseudoHeader(header, _scheme) && value != null)
                        _scheme = HttpScheme.CACHE.get(value);
                    _request = true;
                    break;

                case C_AUTHORITY:
                    if(checkPseudoHeader(header, _authority))
                    {
                        if (field instanceof HostPortHttpField)
                            _authority = (HostPortHttpField)field;
                        else if (value != null)
                            _authority = new AuthorityHttpField(value);
                    }
                    _request = true;
                    break;

                case HOST:
                    // :authority fields must come first.  If we have one, ignore the host header as far as authority goes.
                    if (_authority==null)
                    {
                        if (field instanceof HostPortHttpField)
                            _authority = (HostPortHttpField)field;
                        else if (value != null)
                            _authority = new AuthorityHttpField(value);
                    }
                    _fields.add(field);
                    break;

                case C_PATH:
                    if(checkPseudoHeader(header, _path))
                        _path = value;
                    _request = true;
                    break;

                case CONTENT_LENGTH:
                    _contentLength = field.getLongValue();
                    _fields.add(field);
                    break;
                
                case TE:
                    if ("trailers".equalsIgnoreCase(value))
                        _fields.add(field);
                    else
                        streamException("Unsupported TE value %s", value);
                    break;
            
                case CONNECTION:
                    // TODO should other connection specific fields be listed here?
                    streamException("Connection specific field %s", header);
                    break;                

                default:               
                    if (name.charAt(0)==':')
                        streamException("Unknown pseudo header %s", name);
                    else
                        _fields.add(field);
                    break;
            }
        }
        else
        {
            if (name.charAt(0)==':')
                streamException("Unknown pseudo header %s",name);
            else
                _fields.add(field);
        }
    }

    void streamException(String messageFormat, Object... args)
    {
        HpackException.StreamException stream = new HpackException.StreamException(messageFormat, args);
        if (_streamException==null)
            _streamException = stream;
        else
            _streamException.addSuppressed(stream);
    }

    private boolean checkHeader(HttpHeader header, int value)
    {
        if (_fields.size()>0)
        {
            streamException("Pseudo header %s after fields", header.asString());
            return false;
        }
        if (value==-1)
            return true;
        streamException("Duplicate pseudo header %s", header.asString());
        return false;
    }

    private boolean checkPseudoHeader(HttpHeader header, Object value)
    {
        if (_fields.size()>0)
        {
            streamException("Pseudo header %s after fields", header.asString());
            return false;
        }
        if (value==null)
            return true;
        streamException("Duplicate pseudo header %s", header.asString());
        return false;
    }

    public MetaData build() throws HpackException.StreamException
    {
        if (_streamException!=null)
        {
            _streamException.addSuppressed(new Throwable());
            throw _streamException;
        }
            
        if (_request && _response)
            throw new HpackException.StreamException("Request and Response headers");
            
        try
        {
            HttpFields fields = _fields;
            _fields = new HttpFields(Math.max(10,fields.size()+5));

            if (_method!=null || _path!=null || _authority!=null || _scheme!=null)
                return new MetaData.Request(_method,_scheme,_authority,_path,HttpVersion.HTTP_2,fields,_contentLength);
            if (_status>0)
                return new MetaData.Response(HttpVersion.HTTP_2,_status,fields,_contentLength);
                
            return new MetaData(HttpVersion.HTTP_2,fields,_contentLength);
        }
        finally
        {
            _status=0;
            _method=null;
            _scheme=null;
            _authority=null;
            _path=null;
            _size=0;
            _contentLength=Long.MIN_VALUE;
        }
    }

    /**
     * Check that the max size will not be exceeded.
     * @param length the length
     * @param huffman the huffman name
     * @throws SessionException 
     */
    public void checkSize(int length, boolean huffman) throws SessionException
    {
        // Apply a huffman fudge factor
        if (huffman)
            length=(length*4)/3;
        if ((_size+length)>_maxSize)
            throw new HpackException.SessionException("Header too large %d > %d", _size+length, _maxSize);
    }
}
