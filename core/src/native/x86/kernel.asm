; -----------------------------------------------
; $Id$
;
; Main kernel startup code
;
; Author       : E. Prangsma
; -----------------------------------------------

    global sys_start

;
; Kernel startup code
;
; Parameters
;   EAX=0x2BADB002 (Multiboot magic)
;   EBX=ref to multiboot structure
;
sys_start:
	jmp real_start

	; MULTI-BOOT HEADER
	align 4
mb_header:
	dd 0x1BADB002				; Magic
	dd 0x00010003				; Feature flags
	dd 0-0x1BADB002-0x00010003	; Checksum
	dd mb_header				; header_addr
	dd sys_start				; load_addr
	dd 0 						; load_end_addr (patched up by BootImageBuilder)
	dd 0						; bss_end_addr
	dd real_start				; entry_addr

real_start:
	mov esp,Lkernel_esp
	cld
	call sys_clrscr

	cmp eax,0x2BADB002
	je multiboot_ok
	jmp no_multiboot_loader

multiboot_ok:
	; Copy the multiboot info block
	cld
	mov esi,ebx
	mov edi,multiboot_info
	mov ecx,MBI_SIZE
	rep movsb

	; Copy command line (if any)
	mov esi,[multiboot_info+MBI_CMDLINE]
	test esi,0xFFFFFFFF
	jz skip_multiboot_cmdline
	mov edi,multiboot_cmdline
	mov ecx,MBI_CMDLINE_MAX
	rep movsb
skip_multiboot_cmdline:

	mov ebx,[multiboot_info+MBI_MEMUPPER]	; MB upper mem
	shl ebx,10			; Convert KB -> bytes
	add ebx,0x100000	; MB upper mem starts at 1Mb

    ; Check that A20 is really enabled
    xor eax,eax
check_a20:
    inc eax
    mov dword [0x0],eax
    cmp eax, dword [0x100000]
    je check_a20 ; Just loop if this is not good.

    mov eax,sys_version
    call sys_print_str

	call Lsetup_mm
	call Lsetup_idt

    mov eax,sys_version
    call sys_print_str

	mov eax,before_sti_msg
	call sys_print_str

	; Initialized the FPU
	fninit

	;sti

	mov eax,before_start_vm_msg
	call sys_print_str

	; Go into userspace
	push dword USER_DS	; old SS
	push Luser_esp		; old ESP
	pushf				; old EFLAGS
	push dword USER_CS	; old CS
	push go_user_cs		; old EIP
	pushf
	pop eax
	and eax,~F_NT
	push eax
	popf
	iret
;	db 0xea
;	dd go_user_cs
;	dw USER_CS

no_multiboot_loader:
    mov eax,no_multiboot_loader_msg
    call sys_print_str
    jmp _halt

go_user_cs:
	mov eax,USER_DS
	mov ss,ax
	mov esp,Luser_esp
	mov ds,ax
	mov es,ax
	mov gs,ax
	mov eax,CURPROC_FS
	mov fs,ax
	sti

	; Set tracing on
	%if 0
		pushf
		pop eax
		or eax,F_TF
		push eax
		popf
	%endif

	call sys_clrscr

	; Now start the virtual machine
	xor ebp,ebp ; Clear the frame ptr
	push ebp    ; previous EBP
	push ebp    ; MAGIC    (here invalid ON PURPOSE!)
	push ebp    ; PC           (here invalid ON PURPOSE!)
	push ebp    ; VmMethod (here invalid ON PURPOSE!)
	mov ebp,esp

	mov eax,vm_start
	add eax,BootImageBuilder_JUMP_MAIN_OFFSET
	call eax

	push eax
	mov eax,after_vm_msg
	call sys_print_str
	pop eax
	call sys_print_eax
	mov eax,esp
	call sys_print_eax
	jmp _halt

_halt:
	hlt
	jmp _halt

no_multiboot_loader_msg: db 'No multiboot loader. halt...',0;
before_sti_msg:          db 'Before STI',0xd,0xa,0
before_start_vm_msg:     db 'Before start_vm',0xd,0xa,0
after_vm_msg:  			 db 'VM returned with EAX ',0

multiboot_info:
	times MBI_SIZE db  0

multiboot_cmdline:
	times (MBI_CMDLINE_MAX+4) db 0

