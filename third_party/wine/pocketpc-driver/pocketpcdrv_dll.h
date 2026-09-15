#ifndef __WINE_POCKETPCDRV_DLL_H
#define __WINE_POCKETPCDRV_DLL_H

#include <stdarg.h>
#include "windef.h"
#include "winbase.h"
#include "unixlib.h"

#define POCKETPCDRV_UNIX_CALL(func, params) \
    WINE_UNIX_CALL(pocketpcdrv_unix_func_ ## func, params)

#endif
