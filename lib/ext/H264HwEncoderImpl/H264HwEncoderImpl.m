//
//  H264HwEncoderImpl.m
//  h264v1
//
//  Created by Ganvir, Manish on 3/31/15.
//  Copyright (c) 2015 Ganvir, Manish. All rights reserved.
//

#import "H264HwEncoderImpl.h"

#import <VideoToolbox/VideoToolbox.h>
#import <AVFoundation/AVFoundation.h>

@implementation H264HwEncoderImpl
{
    VTCompressionSessionRef EncodingSession;
    dispatch_queue_t aQueue;
    CMFormatDescriptionRef  format;
    CMSampleTimingInfo * timingInfo;
    BOOL initialized;
    NSData *sps;
    NSData *pps;
    int encodeWidth;
    int encodeHeight;
    int averageBitRate;
    int frameRate;
}
@synthesize error;

- (void) initWithConfiguration
{
    EncodingSession = nil;
    initialized = true;
    // Serial, so encode/stop/reconfigure never touch the session at the same time
    aQueue = dispatch_queue_create("H264HwEncoderImpl", DISPATCH_QUEUE_SERIAL);
    sps = NULL;
    pps = NULL;
    averageBitRate = 450000;
    frameRate = 16;

}

void didCompressH264(void *outputCallbackRefCon, void *sourceFrameRefCon, OSStatus status, VTEncodeInfoFlags infoFlags,
                     CMSampleBufferRef sampleBuffer )
{
    //    NSLog(@"didCompressH264 called with status %d infoFlags %d", (int)status, (int)infoFlags);
    if (status != 0) return;
    
    if (!CMSampleBufferDataIsReady(sampleBuffer))
    {
        NSLog(@"didCompressH264 data is not ready ");
        return;
    }
    H264HwEncoderImpl* encoder = (__bridge H264HwEncoderImpl*)outputCallbackRefCon;
    
    // Check if we have got a key frame first
    bool keyframe = !CFDictionaryContainsKey( (CFArrayGetValueAtIndex(CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, true), 0)), kCMSampleAttachmentKey_NotSync);
    
    if (keyframe)
    {
        CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sampleBuffer);
        // CFDictionaryRef extensionDict = CMFormatDescriptionGetExtensions(format);
        // Get the extensions
        // From the extensions get the dictionary with key "SampleDescriptionExtensionAtoms"
        // From the dict, get the value for the key "avcC"
        
        size_t sparameterSetSize, sparameterSetCount;
        const uint8_t *sparameterSet;
        OSStatus statusCode = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 0, &sparameterSet, &sparameterSetSize, &sparameterSetCount, 0 );
        if (statusCode == noErr)
        {
            // Found sps and now check for pps
            size_t pparameterSetSize, pparameterSetCount;
            const uint8_t *pparameterSet;
            OSStatus statusCode = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 1, &pparameterSet, &pparameterSetSize, &pparameterSetCount, 0 );
            if (statusCode == noErr)
            {
                // Found pps
                encoder->sps = [NSData dataWithBytes:sparameterSet length:sparameterSetSize];
                encoder->pps = [NSData dataWithBytes:pparameterSet length:pparameterSetSize];
                if (encoder->_delegate)
                {
                    [encoder->_delegate gotSpsPps:encoder->sps pps:encoder->pps];
                }
            }
        }
    }
    
    CMBlockBufferRef dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer);
    size_t length, totalLength;
    char *dataPointer;
    OSStatus statusCodeRet = CMBlockBufferGetDataPointer(dataBuffer, 0, &length, &totalLength, &dataPointer);
    if (statusCodeRet == noErr) {
        
        size_t bufferOffset = 0;
        static const int AVCCHeaderLength = 4;
        while (bufferOffset < totalLength - AVCCHeaderLength) {
            
            // Read the NAL unit length
            uint32_t NALUnitLength = 0;
            memcpy(&NALUnitLength, dataPointer + bufferOffset, AVCCHeaderLength);
            
            // Convert the length value from Big-endian to Little-endian
            NALUnitLength = CFSwapInt32BigToHost(NALUnitLength);
            
            NSData* data = [[NSData alloc] initWithBytes:(dataPointer + bufferOffset + AVCCHeaderLength) length:NALUnitLength];
            [encoder->_delegate gotEncodedData:data isKeyFrame:keyframe];
            
            // Move to the next NAL unit in the block buffer
            bufferOffset += AVCCHeaderLength + NALUnitLength;
        }
        
    }
    
}

- (void) encode:(CMSampleBufferRef )sampleBuffer
{
    dispatch_sync(aQueue, ^{
        
        // Recreate the session if a previous frame failed (e.g. after the app was backgrounded)
        if (!self->EncodingSession && ![self createSession]) {
            return;
        }

        // Get the CV Image buffer
        CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
        
        // Create properties
        CMTime presentationTimeStamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer);
        CMTime duration = CMSampleBufferGetDuration(sampleBuffer);
        VTEncodeInfoFlags flags;
        
        // Pass it to the encoder
        OSStatus statusCode = VTCompressionSessionEncodeFrame(self->EncodingSession,
                                                              imageBuffer,
                                                              presentationTimeStamp,
                                                              duration,
                                                              NULL, NULL, &flags);
        // Check for error
        if (statusCode != noErr) {
            NSLog(@"H264: VTCompressionSessionEncodeFrame failed with %d", (int)statusCode);
            self->error = @"H264: VTCompressionSessionEncodeFrame failed ";
            
            // End the session; the next frame creates a fresh one
            VTCompressionSessionInvalidate(self->EncodingSession);
            CFRelease(self->EncodingSession);
            self->EncodingSession = NULL;
            return;
        }
    });
    
    
}

- (void) initEncodeWidth:(int)width height:(int)height
{
    dispatch_sync(aQueue, ^{
        self->encodeWidth = width;
        self->encodeHeight = height;
        [self createSession];
    });
}

// Must be called on aQueue
- (BOOL) createSession
{
    // Create the compression session
    OSStatus status = VTCompressionSessionCreate(NULL, encodeWidth, encodeHeight, kCMVideoCodecType_H264, NULL, NULL, NULL, didCompressH264, (__bridge void *)(self),  &EncodingSession);
    NSLog(@"H264: VTCompressionSessionCreate %d", (int)status);

    if (status != 0)
    {
        NSLog(@"H264: Unable to create a H264 session");
        error = @"H264: Unable to create a H264 session";
        EncodingSession = NULL;

        return NO;
    }

    // Set the properties
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue);
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse);
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Main_3_1);

    // Frames are sent as single UDP packets with no retransmission, so a lost frame
    // corrupts the picture until the next keyframe. Send one every second so the
    // remote recovers quickly, and send parameter sets with each of them.
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, (__bridge CFTypeRef)@(1.0));
    [self applyRateSettings];

    // Tell the encoder to start encoding
    VTCompressionSessionPrepareToEncodeFrames(EncodingSession);

    return YES;
}

// Must be called on aQueue
- (void) applyRateSettings
{
    if (!EncodingSession) {
        return;
    }

    // Without a bitrate cap keyframes can exceed the 64 KB UDP datagram limit and are dropped
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_AverageBitRate, (__bridge CFTypeRef)@(averageBitRate));
    NSArray *dataRateLimits = @[@(averageBitRate / 8 * 3 / 2), @(1.0)];
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_DataRateLimits, (__bridge CFArrayRef)dataRateLimits);
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_ExpectedFrameRate, (__bridge CFTypeRef)@(frameRate));
    VTSessionSetProperty(EncodingSession, kVTCompressionPropertyKey_MaxKeyFrameInterval, (__bridge CFTypeRef)@(frameRate));
}

- (void) setAverageBitRate:(int)bitRate frameRate:(int)fps
{
    dispatch_sync(aQueue, ^{
        self->averageBitRate = bitRate;
        self->frameRate = fps;
        [self applyRateSettings];
    });
}

- (void) stop
{
    dispatch_sync(aQueue, ^{
        if (!self->EncodingSession) {
            return;
        }

        // Mark the completion
        VTCompressionSessionCompleteFrames(self->EncodingSession, kCMTimeInvalid);

        // End the session
        VTCompressionSessionInvalidate(self->EncodingSession);
        CFRelease(self->EncodingSession);
        self->EncodingSession = NULL;
        self->error = NULL;
    });
}

@end
