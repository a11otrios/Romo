#import "RGLBufferVC.h"

@implementation RGLBufferVC

#pragma mark - Initialization --

- (id)initWithFrame:(CGRect)frame
{
    if (self = [super init])
    {
        _displayLayer = [[AVSampleBufferDisplayLayer alloc] init];

        _displayLayer.bounds = frame;
        _displayLayer.frame = frame;
        _displayLayer.backgroundColor = [UIColor blackColor].CGColor;
        _displayLayer.position = CGPointMake(CGRectGetMidX(frame), CGRectGetMidY(frame));

        [self.view.layer addSublayer:_displayLayer];
    }

    return self;
}

- (void) playSampleBuffer:(CMSampleBufferRef) sampleBuffer
{
    CFRetain(sampleBuffer);

    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, YES);
    CFMutableDictionaryRef dict = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
    CFDictionarySetValue(dict, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);

    // A single corrupt or incomplete frame puts the layer into the failed state,
    // after which it silently drops everything. Flush it so the next keyframe can display.
    if (_displayLayer.status == AVQueuedSampleBufferRenderingStatusFailed) {
        NSLog(@"Display layer failed: %@", _displayLayer.error);
        [_displayLayer flush];
    }

    if([_displayLayer isReadyForMoreMediaData])
    {
        [_displayLayer enqueueSampleBuffer:sampleBuffer];
    }
    else
    {
        NSLog(@"Not Ready...");
    }

    CFRelease(sampleBuffer);
}

@end
