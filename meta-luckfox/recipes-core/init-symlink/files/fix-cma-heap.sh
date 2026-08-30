#!/bin/sh
# Create cma-uncached symlink if reserved heap exists
if [ -e /dev/dma_heap/reserved ] && [ ! -e /dev/dma_heap/cma-uncached ]; then
    ln -s /dev/dma_heap/reserved /dev/dma_heap/cma-uncached
fi
