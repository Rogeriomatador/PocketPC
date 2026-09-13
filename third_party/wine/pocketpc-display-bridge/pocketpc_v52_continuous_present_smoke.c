#define COBJMACROS
#define UNICODE
#define _UNICODE
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
#include <stdio.h>

#define POCKETPC_V52_SMOKE_FRAME_COUNT 8
#define POCKETPC_V52_SMOKE_WIDTH 160
#define POCKETPC_V52_SMOKE_HEIGHT 96

static const float k_frame_colors[POCKETPC_V52_SMOKE_FRAME_COUNT][4] = {
    {1.00f, 0.00f, 0.00f, 1.00f},
    {0.00f, 1.00f, 0.00f, 1.00f},
    {0.00f, 0.00f, 1.00f, 1.00f},
    {1.00f, 1.00f, 0.00f, 1.00f},
    {1.00f, 0.00f, 1.00f, 1.00f},
    {0.00f, 1.00f, 1.00f, 1.00f},
    {0.25f, 0.50f, 1.00f, 1.00f},
    {1.00f, 0.50f, 0.25f, 1.00f},
};

static LRESULT CALLBACK wndproc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam)
{
    (void)wparam;
    (void)lparam;
    if (message == WM_CLOSE)
    {
        DestroyWindow(hwnd);
        return 0;
    }
    if (message == WM_DESTROY)
    {
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, message, wparam, lparam);
}

static int pump_messages(void)
{
    MSG message;
    while (PeekMessageW(&message, NULL, 0, 0, PM_REMOVE))
    {
        if (message.message == WM_QUIT)
            return 0;
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    return 1;
}

int main(void)
{
    HINSTANCE instance = GetModuleHandleW(NULL);
    WNDCLASSW klass;
    HWND window = NULL;
    DXGI_SWAP_CHAIN_DESC desc;
    IDXGISwapChain *swapchain = NULL;
    ID3D11Device *device = NULL;
    ID3D11DeviceContext *context = NULL;
    ID3D11Texture2D *backbuffer = NULL;
    ID3D11RenderTargetView *target = NULL;
    D3D_FEATURE_LEVEL feature = 0;
    HRESULT hr;
    unsigned int frame;
    int result = 1;

    ZeroMemory(&klass, sizeof(klass));
    klass.lpfnWndProc = wndproc;
    klass.hInstance = instance;
    klass.hCursor = LoadCursorW(NULL, IDC_ARROW);
    klass.lpszClassName = L"PocketPcV52ContinuousPresentSmoke";
    if (!RegisterClassW(&klass) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS)
    {
        printf("POCKETPC_V52_SMOKE_REGISTER_FAILED error=%lu\n", (unsigned long)GetLastError());
        return 10;
    }

    window = CreateWindowExW(
        0,
        klass.lpszClassName,
        L"PocketPC v52 Continuous Present Smoke",
        WS_OVERLAPPEDWINDOW | WS_VISIBLE,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        POCKETPC_V52_SMOKE_WIDTH,
        POCKETPC_V52_SMOKE_HEIGHT,
        NULL,
        NULL,
        instance,
        NULL
    );
    if (!window)
    {
        printf("POCKETPC_V52_SMOKE_WINDOW_FAILED error=%lu\n", (unsigned long)GetLastError());
        return 11;
    }

    ZeroMemory(&desc, sizeof(desc));
    desc.BufferDesc.Width = POCKETPC_V52_SMOKE_WIDTH;
    desc.BufferDesc.Height = POCKETPC_V52_SMOKE_HEIGHT;
    desc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    desc.BufferCount = 2;
    desc.OutputWindow = window;
    desc.Windowed = TRUE;
    desc.SwapEffect = DXGI_SWAP_EFFECT_DISCARD;

    hr = D3D11CreateDeviceAndSwapChain(
        NULL,
        D3D_DRIVER_TYPE_HARDWARE,
        NULL,
        0,
        NULL,
        0,
        D3D11_SDK_VERSION,
        &desc,
        &swapchain,
        &device,
        &feature,
        &context
    );
    if (FAILED(hr))
    {
        printf("POCKETPC_V52_SMOKE_DEVICE_FAILED hr=0x%08lx\n", (unsigned long)hr);
        result = 12;
        goto cleanup;
    }

    hr = IDXGISwapChain_GetBuffer(swapchain, 0, &IID_ID3D11Texture2D, (void **)&backbuffer);
    if (FAILED(hr))
    {
        printf("POCKETPC_V52_SMOKE_BACKBUFFER_FAILED hr=0x%08lx\n", (unsigned long)hr);
        result = 13;
        goto cleanup;
    }

    hr = ID3D11Device_CreateRenderTargetView(device, (ID3D11Resource *)backbuffer, NULL, &target);
    if (FAILED(hr))
    {
        printf("POCKETPC_V52_SMOKE_RTV_FAILED hr=0x%08lx\n", (unsigned long)hr);
        result = 14;
        goto cleanup;
    }

    printf("POCKETPC_V52_SMOKE_BEGIN frames=%u width=%u height=%u feature=0x%x\n",
           POCKETPC_V52_SMOKE_FRAME_COUNT,
           POCKETPC_V52_SMOKE_WIDTH,
           POCKETPC_V52_SMOKE_HEIGHT,
           (unsigned int)feature);
    fflush(stdout);

    for (frame = 0; frame < POCKETPC_V52_SMOKE_FRAME_COUNT; ++frame)
    {
        unsigned int sequence = frame + 1;
        unsigned long long guest_ready = (unsigned long long)sequence * 2ull - 1ull;
        unsigned long long host_consumed = (unsigned long long)sequence * 2ull;

        if (!pump_messages())
        {
            printf("POCKETPC_V52_SMOKE_ABORTED seq=%u\n", sequence);
            result = 15;
            goto cleanup;
        }

        ID3D11DeviceContext_ClearRenderTargetView(context, target, k_frame_colors[frame]);
        printf(
            "POCKETPC_V52_SMOKE_FRAME_BEFORE_PRESENT seq=%u guestReady=%llu hostConsumed=%llu color=%u\n",
            sequence,
            guest_ready,
            host_consumed,
            frame
        );
        fflush(stdout);

        hr = IDXGISwapChain_Present(swapchain, 0, 0);
        if (FAILED(hr))
        {
            printf("POCKETPC_V52_SMOKE_PRESENT_FAILED seq=%u hr=0x%08lx\n",
                   sequence,
                   (unsigned long)hr);
            result = 20 + (int)frame;
            goto cleanup;
        }

        printf("POCKETPC_V52_SMOKE_FRAME_PRESENT_RETURNED seq=%u\n", sequence);
        fflush(stdout);
        Sleep(40);
    }

    printf("POCKETPC_V52_CONTINUOUS_PRESENT_SMOKE_OK frames=%u\n", POCKETPC_V52_SMOKE_FRAME_COUNT);
    fflush(stdout);
    result = 0;

cleanup:
    if (target)
        ID3D11RenderTargetView_Release(target);
    if (backbuffer)
        ID3D11Texture2D_Release(backbuffer);
    if (context)
        ID3D11DeviceContext_Release(context);
    if (device)
        ID3D11Device_Release(device);
    if (swapchain)
        IDXGISwapChain_Release(swapchain);
    if (window)
        DestroyWindow(window);
    return result;
}
