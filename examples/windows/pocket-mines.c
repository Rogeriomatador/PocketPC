/*
 * Pocket Mines - original Win64/GDI mini game for PocketPC.
 * No Direct3D, audio, network, installer, or external assets.
 * The source is distributed as an example, NOT evidence of Android compatibility.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <windowsx.h>
#include <stdlib.h>
#include <stdio.h>

#define GRID 8
#define MINES 10
#define CELL 42
#define PAD 14
#define HEADER 72
#define BOARD_PX (GRID * CELL)
#define VIEW_W (PAD * 2 + BOARD_PX)
#define VIEW_H (PAD * 2 + HEADER + BOARD_PX)

static int mine[GRID][GRID];
static int opened[GRID][GRID];
static int flagged[GRID][GRID];
static int near_count[GRID][GRID];
static int started = 0;
static int ended = 0;
static int won = 0;
static int lives = 1;
static int flags = 0;
static int cleared = 0;

static int inside(int x, int y) {
    return x >= 0 && x < GRID && y >= 0 && y < GRID;
}

static void new_game(void) {
    ZeroMemory(mine, sizeof mine);
    ZeroMemory(opened, sizeof opened);
    ZeroMemory(flagged, sizeof flagged);
    ZeroMemory(near_count, sizeof near_count);
    started = ended = won = flags = cleared = 0;
    lives = 1;
}

static void place_mines(int safe_x, int safe_y) {
    int locations[GRID * GRID - 1];
    int count = 0;
    int x, y, i;
    srand((unsigned int)GetTickCount());
    for (y = 0; y < GRID; y++) {
        for (x = 0; x < GRID; x++) {
            if (x != safe_x || y != safe_y) {
                locations[count++] = y * GRID + x;
            }
        }
    }
    for (i = count - 1; i > 0; i--) {
        int index = rand() % (i + 1);
        int tmp = locations[i];
        locations[i] = locations[index];
        locations[index] = tmp;
    }
    for (i = 0; i < MINES; i++) {
        mine[locations[i] / GRID][locations[i] % GRID] = 1;
    }
    for (y = 0; y < GRID; y++) {
        for (x = 0; x < GRID; x++) {
            int dx, dy, total = 0;
            for (dy = -1; dy <= 1; dy++) {
                for (dx = -1; dx <= 1; dx++) {
                    if (inside(x + dx, y + dy)) {
                        total += mine[y + dy][x + dx];
                    }
                }
            }
            near_count[y][x] = total;
        }
    }
    started = 1;
}

static void reveal(int x, int y) {
    int dx, dy;
    if (!inside(x, y) || opened[y][x] || flagged[y][x] || ended) {
        return;
    }
    opened[y][x] = 1;
    if (mine[y][x]) {
        ended = 1;
        won = 0;
        lives = 0;
        return;
    }
    cleared++;
    if (cleared == GRID * GRID - MINES) {
        ended = won = 1;
        return;
    }
    if (near_count[y][x] != 0) {
        return;
    }
    for (dy = -1; dy <= 1; dy++) {
        for (dx = -1; dx <= 1; dx++) {
            if (dx != 0 || dy != 0) {
                reveal(x + dx, y + dy);
            }
        }
    }
}

static void tile_click(HWND hwnd, int px, int py, int right) {
    int x = (px - PAD) / CELL;
    int y = (py - PAD - HEADER) / CELL;
    if (px < PAD || py < PAD + HEADER ||
        px >= PAD + BOARD_PX || py >= PAD + HEADER + BOARD_PX) {
        return;
    }
    if (!inside(x, y) || ended || opened[y][x]) {
        return;
    }
    if (right) {
        flagged[y][x] = !flagged[y][x];
        flags += flagged[y][x] ? 1 : -1;
    } else {
        if (flagged[y][x]) return;
        if (!started) place_mines(x, y);
        reveal(x, y);
    }
    InvalidateRect(hwnd, NULL, FALSE);
}

static void solid_rect(HDC dc, const RECT *rect, COLORREF color) {
    HBRUSH brush = CreateSolidBrush(color);
    FillRect(dc, rect, brush);
    DeleteObject(brush);
}

static void print_centered(HDC dc, const RECT *rect, const char *text,
                           COLORREF color) {
    RECT copy = *rect;
    SetTextColor(dc, color);
    SetBkMode(dc, TRANSPARENT);
    DrawTextA(dc, text, -1, &copy, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
}

static void draw_board(HWND hwnd, HDC dc) {
    RECT client;
    RECT title;
    RECT instructions;
    int x, y;
    char info[128];
    HFONT font = (HFONT)GetStockObject(DEFAULT_GUI_FONT);
    SelectObject(dc, font);
    GetClientRect(hwnd, &client);
    solid_rect(dc, &client, RGB(23, 29, 43));

    title.left = PAD;
    title.top = PAD;
    title.right = VIEW_W - PAD;
    title.bottom = PAD + 31;
    print_centered(dc, &title, "POCKET MINES  |  WIN64", RGB(157, 209, 255));

    instructions = title;
    instructions.top = title.bottom + 2;
    instructions.bottom = instructions.top + 31;
    if (ended) {
        lstrcpyA(info, won ? "YOU WIN!  Press R to restart" :
                           "BOOM!  Press R to restart");
    } else {
        wsprintfA(info, "Mines: %d   Flags: %d   Left: open | Right: flag",
                  MINES, flags);
    }
    print_centered(dc, &instructions, info, RGB(226, 236, 250));

    for (y = 0; y < GRID; y++) {
        for (x = 0; x < GRID; x++) {
            RECT tile;
            char text[3] = {0};
            COLORREF fill = RGB(52, 66, 86);
            COLORREF foreground = RGB(238, 241, 248);
            tile.left = PAD + x * CELL + 2;
            tile.top = PAD + HEADER + y * CELL + 2;
            tile.right = tile.left + CELL - 4;
            tile.bottom = tile.top + CELL - 4;
            if (opened[y][x]) {
                fill = RGB(34, 43, 57);
                if (mine[y][x]) {
                    fill = RGB(188, 73, 81);
                    lstrcpyA(text, "*");
                } else if (near_count[y][x]) {
                    wsprintfA(text, "%d", near_count[y][x]);
                    foreground = RGB(144, 209, 255);
                }
            } else if (ended && mine[y][x]) {
                fill = RGB(137, 61, 71);
                lstrcpyA(text, "*");
            } else if (flagged[y][x]) {
                fill = RGB(73, 101, 121);
                lstrcpyA(text, "F");
                foreground = RGB(255, 213, 111);
            }
            solid_rect(dc, &tile, fill);
            if (text[0]) print_centered(dc, &tile, text, foreground);
        }
    }
}

static LRESULT CALLBACK game_proc(HWND hwnd, UINT msg,
                                   WPARAM wparam, LPARAM lparam) {
    switch (msg) {
    case WM_CREATE:
        new_game();
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT paint;
        HDC dc = BeginPaint(hwnd, &paint);
        draw_board(hwnd, dc);
        EndPaint(hwnd, &paint);
        return 0;
    }
    case WM_LBUTTONUP:
        tile_click(hwnd, GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam), 0);
        return 0;
    case WM_RBUTTONUP:
        tile_click(hwnd, GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam), 1);
        return 0;
    case WM_KEYDOWN:
        if (wparam == 'R' || wparam == VK_F2) {
            new_game();
            InvalidateRect(hwnd, NULL, FALSE);
            return 0;
        }
        if (wparam == VK_ESCAPE) {
            DestroyWindow(hwnd);
            return 0;
        }
        break;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcA(hwnd, msg, wparam, lparam);
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous,
                   LPSTR command, int show) {
    WNDCLASSA klass;
    RECT size = {0, 0, VIEW_W, VIEW_H};
    HWND window;
    MSG msg;
    (void)previous;
    (void)command;
    ZeroMemory(&klass, sizeof klass);
    klass.lpfnWndProc = game_proc;
    klass.hInstance = instance;
    klass.lpszClassName = "PocketMinesWindow64";
    klass.hCursor = LoadCursorA(NULL, IDC_ARROW);
    klass.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    if (!RegisterClassA(&klass)) return 2;
    AdjustWindowRect(&size, WS_OVERLAPPEDWINDOW, FALSE);
    window = CreateWindowExA(0, klass.lpszClassName,
        "Pocket Mines - PocketPC Win64 GDI",
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT,
        size.right - size.left, size.bottom - size.top,
        NULL, NULL, instance, NULL);
    if (!window) return 3;
    ShowWindow(window, show);
    UpdateWindow(window);
    while (GetMessageA(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageA(&msg);
    }
    return (int)msg.wParam;
}
