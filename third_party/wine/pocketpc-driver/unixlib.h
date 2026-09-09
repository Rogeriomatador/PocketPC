#ifndef __WINE_POCKETPCDRV_UNIXLIB_H
#define __WINE_POCKETPCDRV_UNIXLIB_H

#include <stdarg.h>
#include "winternl.h"
#include "wine/unixlib.h"

enum pocketpcdrv_unix_func
{
    pocketpcdrv_unix_func_init,
    pocketpcdrv_unix_func_count,
};

#endif
