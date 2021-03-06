package com.enonic.lib.http.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.common.net.MediaType;

import okhttp3.Cookie;
import okhttp3.Headers;
import okhttp3.Response;

import com.enonic.xp.script.serializer.MapGenerator;
import com.enonic.xp.script.serializer.MapSerializable;

import static com.enonic.lib.http.client.HttpRequestHandler.MAX_IN_MEMORY_BODY_STREAM_BYTES;
import static org.apache.commons.lang.StringUtils.isBlank;

public final class ResponseMapper
    implements MapSerializable
{
    private final static ImmutableSet<String> SKIP_HEADERS =
        ImmutableSet.of( "okhttp-received-millis", "okhttp-selected-protocol", "okhttp-sent-millis" );

    private final static ImmutableList<MediaType> TEXT_CONTENT_TYPES =
        ImmutableList.of( MediaType.ANY_TEXT_TYPE, MediaType.create( "application", "xml" ), MediaType.create( "application", "json" ),
                          MediaType.create( "application", "javascript" ), MediaType.create( "application", "soap+xml" ),
                          MediaType.create( "application", "xml" ) );

    private final CookieJar cookieJar;

    private final int status;

    private final String message;

    private final Headers headers;

    private final String contentType;

    private final ByteSource bodySource;

    private final String bodyString;

    public ResponseMapper( final Response response, final CookieJar cookieJar )
    {
        this.cookieJar = cookieJar;
        try
        {
            this.status = response.code();
            this.message = response.message();
            this.headers = response.headers();
            this.contentType = this.headers.get( "content-type" );

            final boolean isHeadMethod = "HEAD".equalsIgnoreCase( response.request().method() );
            this.bodySource = isHeadMethod ? ByteSource.empty() : getResponseBodyStream( response );
            this.bodyString = isHeadMethod ? "" : getResponseBodyString( bodySource );
        }
        finally
        {
            response.body().close();
        }
    }

    @Override
    public void serialize( final MapGenerator gen )
    {
        gen.value( "status", this.status );
        gen.value( "message", this.message );

        gen.value( "body", this.bodyString );
        gen.value( "bodyStream", this.bodySource );
        gen.value( "contentType", this.contentType );

        serializeHeaders( "headers", gen, this.headers );
        serializeCookies( "cookies", gen, this.cookieJar.getCookies() );
    }

    private Charset getCharset()
    {
        if ( this.contentType == null )
        {
            return StandardCharsets.UTF_8;
        }
        try
        {
            final MediaType type = MediaType.parse( this.contentType );
            return type.charset().or( StandardCharsets.UTF_8 );
        }
        catch ( IllegalArgumentException e )
        {
            return StandardCharsets.UTF_8;
        }
    }

    private String getResponseBodyString( final ByteSource source )
    {
        try
        {
            return isTextContent() ? source.asCharSource( getCharset() ).read() : null;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
    }

    private ByteSource getResponseBodyStream( final Response response )
    {
        try
        {
            final long bodyLength = response.body().contentLength();
            if ( bodyLength == -1 || bodyLength > MAX_IN_MEMORY_BODY_STREAM_BYTES )
            {
                final File tempFile = writeAsTmpFile( response.body().byteStream() );
                return new RefFileByteSource( tempFile );
            }
            return ByteSource.wrap( response.body().bytes() );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e.getMessage(), e );
        }
    }

    private File writeAsTmpFile( final InputStream inputStream )
        throws IOException
    {
        final File tempFile = File.createTempFile( "xphttp", ".tmp" );
        tempFile.deleteOnExit();
        java.nio.file.Files.copy( inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING );
        return tempFile;
    }

    private void serializeHeaders( final String name, final MapGenerator gen, final Headers headers )
    {
        gen.map( name );
        for ( final String headerName : headers.names() )
        {
            if ( SKIP_HEADERS.contains( headerName.toLowerCase() ) )
            {
                continue;
            }
            gen.value( headerName, headers.get( headerName ) );
        }
        gen.end();
    }

    private void serializeCookies( final String name, final MapGenerator gen, final List<Cookie> cookies )
    {
        gen.array( name );
        for ( final Cookie cookie : cookies )
        {
            gen.map();
            gen.value( "name", cookie.name() );
            gen.value( "value", cookie.value() );
            gen.value( "path", cookie.path() );
            gen.value( "domain", cookie.domain() );
            gen.value( "expires", cookie.expiresAt() );
            gen.value( "secure", cookie.secure() );
            gen.value( "httpOnly", cookie.httpOnly() );
            gen.end();
        }
        gen.end();
    }

    private boolean isTextContent()
    {
        if ( isBlank( this.contentType ) )
        {
            return false;
        }

        try
        {
            final MediaType mediaType = MediaType.parse( this.contentType );
            final String subType = mediaType.subtype() == null ? "" : mediaType.subtype().toLowerCase();
            return TEXT_CONTENT_TYPES.stream().anyMatch( mediaType::is ) || subType.contains( "xml" ) || subType.contains( "json" );
        }
        catch ( IllegalArgumentException e )
        {
            return false;
        }
    }

}
