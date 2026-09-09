#include "pocketpc_display_bridge.h"
#include <stdio.h>

int main(void) {
    struct pdb_connection c;
    struct pdb_pointer_event pointer;
    struct pdb_key_event key;
    struct pdb_frame_presented frame;
    char error[160] = {0};

    if (pdb_connect_from_environment(&c,error,sizeof(error)) != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED connect=%s\n",error); return 80;
    }
    if (pdb_send_window_create(&c,1,0,0,640,360,error,sizeof(error)) != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED create=%s\n",error); pdb_close(&c); return 81;
    }
    if (pdb_send_window_geometry(&c,1,20,30,640,360,1,0,error,sizeof(error)) != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED geometry=%s\n",error); pdb_close(&c); return 82;
    }
    printf("POCKETPC_DISPLAY_BRIDGE_WINDOW_OK id=1\n");

    if (pdb_receive_pointer_event(&c,&pointer,error,sizeof(error)) != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED pointer=%s\n",error); pdb_close(&c); return 83;
    }
    if (pointer.window_id != 1 || pointer.action != 1 || pointer.x != 100 || pointer.y != 80) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED pointer_values\n"); pdb_close(&c); return 84;
    }
    printf("POCKETPC_DISPLAY_BRIDGE_POINTER_OK\n");

    if (pdb_receive_key_event(&c,&key,error,sizeof(error)) != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED key=%s\n",error); pdb_close(&c); return 85;
    }
    if (key.window_id != 1 || key.action != 1 || key.key_code != 65) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED key_values\n"); pdb_close(&c); return 86;
    }
    printf("POCKETPC_DISPLAY_BRIDGE_KEY_OK\n");

    if (pdb_receive_frame_presented(&c,&frame,error,sizeof(error)) != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED frame=%s\n",error); pdb_close(&c); return 87;
    }
    if (frame.window_id != 1 || frame.frame_id != 1 || frame.status != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED frame_values\n"); pdb_close(&c); return 88;
    }
    printf("POCKETPC_DISPLAY_BRIDGE_FRAME_ACK_OK\n");

    if (pdb_send_window_destroy(&c,1,error,sizeof(error)) != 0) {
        fprintf(stderr,"POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED destroy=%s\n",error); pdb_close(&c); return 89;
    }
    printf("POCKETPC_DISPLAY_BRIDGE_SMOKE_OK caps=%u\n",c.negotiated_capabilities);
    pdb_close(&c);
    return 0;
}
