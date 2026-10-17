/* Потоковый аппаратный захват MStar через MTAL VSS для Android ARM32.
   Нативный процесс получает кадры через MTVSS_CaptureOne и передаёт их в stdout.
   Инициализация MTAL/VSS выполняется однократно на запуск процесса.
*/
typedef unsigned int u32;
typedef unsigned char u8;
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);
extern char *dlerror(void);
extern void (*signal(int, void (*)(int)))(int);
static volatile int stop_requested=0;
static void on_stop_signal(int sig){(void)sig;stop_requested=1;}
#define RTLD_NOW 2
// Признак остановки читается между кадрами; в обработчике сигнала драйвер не вызываем.
static long sread(int fd,void*p,u32 n) {
 register long r0 __asm__("r0")=fd;
 register void*r1 __asm__("r1")=p;
 register u32 r2 __asm__("r2")=n;
 register long r7 __asm__("r7")=3;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r2),"r"(r7):"memory");
 return r0;
}
static long sgetpid(void){
 register long r0 __asm__("r0");
 register long r7 __asm__("r7")=20;
 __asm__ volatile("svc #0":"=r"(r0):"r"(r7):"memory");
 return r0;
}
typedef struct {int fd;short events;short revents;} poll_fd;
static int spoll(poll_fd*p,u32 count,int timeout){
 register long r0 __asm__("r0")=(long)p;
 register long r1 __asm__("r1")=count;
 register long r2 __asm__("r2")=timeout;
 register long r7 __asm__("r7")=168;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r2),"r"(r7):"memory");
 return (int)r0;
}
static int should_stop(void){
 if(stop_requested)return 1;
 poll_fd p={0,1,0};
 if(spoll(&p,1,0)>0 && (p.revents&1)){
   u8 cmd=0;
   if(sread(0,&cmd,1)>0)return 1;
 }
 // Закрытый stdin сам по себе не означает остановку: su может не передать его.
 return stop_requested;
}
static long swrite(int fd,const void*p,u32 n) {
 register long r0 __asm__("r0")=fd;
 register const void*r1 __asm__("r1")=p;
 register u32 r2 __asm__("r2")=n;
 register long r7 __asm__("r7")=4;
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r2),"r"(r7):"memory");
 return r0;
}

/* mmap2 на ARM32: анонимная доступная для записи память данного процесса. */
static void *smmap(u32 size) {
 register long r0 __asm__("r0")=0;
 register long r1 __asm__("r1")=size;
 register long r2 __asm__("r2")=3;        /* PROT_READ | PROT_WRITE */
 register long r3 __asm__("r3")=0x22;     /* MAP_PRIVATE | MAP_ANONYMOUS */
 register long r4 __asm__("r4")=-1;
 register long r5 __asm__("r5")=0;
 register long r7 __asm__("r7")=192;      /* __NR_mmap2 */
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r2),"r"(r3),"r"(r4),"r"(r5),"r"(r7):"memory");
 return (void*)r0;
}


static long outfd=1;
static u32 slen(const char*s){u32 i=0;while(s&&s[i])i++;return i;}
static void out(const char*s){if(s)swrite((int)outfd,s,slen(s));}

static void num(int x){unsigned int n=(unsigned int)x;if(x<0){out("-");n=0-n;}
 char b[12];int i=0;do{b[i++]='0'+n%10;n/=10;}while(n&&i<11);
 while(i--)swrite((int)outfd,b+i,1);}
/* Монотонные часы ARM32: только отсчёт времени, без обращения к драйверу. */
typedef struct {long tv_sec; long tv_nsec;} mono_ts;
static int monotime(mono_ts*t){
 register long r0 __asm__("r0")=1; /* CLOCK_MONOTONIC */
 register mono_ts*r1 __asm__("r1")=t;
 register long r7 __asm__("r7")=263; /* __NR_clock_gettime, ARM EABI */
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r7):"memory");
 return (int)r0;
}
static long elapsed_ms(const mono_ts*a,const mono_ts*b){
 return (b->tv_sec-a->tv_sec)*1000L+(b->tv_nsec-a->tv_nsec)/1000000L;
}

static void zero(u8*p,u32 n){while(n--)*p++=0;}
static void put32(u8*p,u32 v){for(int i=0;i<4;i++)p[i]=(u8)(v>>(8*i));}
static void put64addr(u8*p,void*v){put32(p,(u32)v);put32(p+4,0);}
/* Строгий разбор десятичных аргументов: запрет отрицательных чисел и переполнений. */
static int dec(const char*s,u32*value){
 if(!s||!*s)return 0;u32 v=0;
 while(*s){if(*s<'0'||*s>'9')return 0;u32 digit=(u32)(*s++-'0');
  if(v>429496729u||(v==429496729u&&digit>5u))return 0;v=v*10u+digit;}
 *value=v;return 1;
}
/* Пауза между попытками уменьшает нагрузку на исследуемый интерфейс захвата. */
static int sleep_ms(u32 milliseconds){
 struct {long sec; long nanosec;} req={milliseconds/1000u,(long)(milliseconds%1000u)*1000000L};
 register long r0 __asm__("r0")=(long)&req;
 register long r1 __asm__("r1")=0;
 register long r7 __asm__("r7")=162; /* ARM EABI __NR_nanosleep */
 __asm__ volatile("svc #0":"+r"(r0):"r"(r1),"r"(r7):"memory");
 return (int)r0;
}

/* Автономный ARM32-бинарник: программная поддержка беззнакового деления. */
__attribute__((used)) unsigned int __aeabi_uidiv(unsigned int dividend,unsigned int divisor){
 if(!divisor)return 0;
 unsigned int quotient=0;
 for(int bit=31;bit>=0;--bit){
   if((dividend>>bit)>=divisor){dividend-=divisor<<bit;quotient|=1u<<bit;}
 }
 return quotient;
}

/* stdout: ширина и высота (u32, little-endian), далее пиксели RGB888.
 * stderr: диагностические сообщения. Инициализация MTAL/VSS однократная;
 * CaptureOne вызывается для каждого кадра, DisconnectEx — при завершении.
 * Без двойного fork и записи кадров в файловую систему.
 */
static void eprint(const char *s){ long keep=outfd; outfd=2;out(s);outfd=keep; }
static void ecode(const char *s,int n){long keep=outfd;outfd=2;out(s);num(n);out("\n");outfd=keep;}
static int finish_vss(void*disconnect,int code){
 int ret=((int(*)(u32))disconnect)(0);
 if(ret)ecode("VSS disconnect ret=",ret);
 return code;
}
static int send_all(const u8*buf,u32 n){
 u32 done=0;while(done<n){long rc=swrite(1,buf+done,n-done);if(rc<=0)return 0;done+=(u32)rc;}return 1;
}
__attribute__((used,noinline)) static int entry(u32*sp){
 u32 argc=sp[0];const char**argv=(const char**)(sp+1);
 u32 x=0,y=0,srcw=0,srch=0,w=0,h=0,fps=0;
 if(argc!=8||!dec(argv[1],&x)||!dec(argv[2],&y)||!dec(argv[3],&srcw)||
   !dec(argv[4],&srch)||!dec(argv[5],&w)||!dec(argv[6],&h)||!dec(argv[7],&fps)){
   eprint("usage: libmstar_vss_server.so X Y SRC_W SRC_H OUT_W OUT_H FPS\n");return 2;
 }
 if(srcw==0||srch==0||((unsigned long long)x+srcw)>0xffffffffull||((unsigned long long)y+srch)>0xffffffffull||
    w==0||h==0||((unsigned long long)srcw*h > (unsigned long long)srch*w ? (unsigned long long)srcw*h - (unsigned long long)srch*w : (unsigned long long)srch*w - (unsigned long long)srcw*h) > (unsigned long long)srcw||
    fps==0||((unsigned long long)w*h)>((0xffffffffull-256ull)/4ull)){eprint("ERROR invalid geometry or fps\n");return 2;}
 // Сообщаем PID до обращения к библиотеке производителя, чтобы остановка
 // работала даже если инициализация VSS надолго зависнет.
 eprint("MSTAR_PID=");{long keep=outfd;outfd=2;num((int)sgetpid());out("\n");outfd=keep;}
 const u32 pixlen=w*h*4u,rgblen=w*h*3u;
 u8*pix=(u8*)smmap(pixlen+256u),*rgb=(u8*)smmap(rgblen);
 if((u32)pix>=0xfffff001u||(u32)rgb>=0xfffff001u){eprint("mmap failed\n");return 3;}
 void*lib=dlopen("/vendor/lib/libmtal.so",RTLD_NOW);
 if(!lib){eprint("MTAL dlopen failed\n");return 4;}
 void*init=dlsym(lib,"MTAL_Init"),*vss=dlsym(lib,"MTVSS_Init"),
   *cap=dlsym(lib,"MTVSS_CaptureOne"),*disc=dlsym(lib,"MTVSS_DisconnectEx");
 if(!init||!vss||!cap||!disc){eprint("Missing MTAL symbol\n");return 5;}
 int st=((int(*)(void))init)();
 if(st){ecode("MTAL_Init error=",st);return 6;}
 st=((int(*)(void))vss)();
 if(st){ecode("MTVSS_Init error=",st);return 7;}
 // SIGPIPE не должен прерывать процесс до освобождения VSS.
 signal(13,(void(*)(int))1);
 signal(15,on_stop_signal);
 eprint("MStar VSS stream started\n");
 u8 pkt[72],result[64],hdr[8];zero(pkt,72);zero(hdr,8);
 put32(pkt,x);put32(pkt+4,y);put32(pkt+8,srcw);put32(pkt+12,srch);
 put32(pkt+0x10,pixlen);put32(pkt+0x14,w);put32(pkt+0x20,0);
 put64addr(pkt+0x28,pix);put32(pkt+0x44,h);
 put32(hdr,w);put32(hdr+4,h);
 u32 frame=0,paced_frames=0;int consecutive_error=0;
 mono_ts origin={0},ts={0};monotime(&origin);
 while(1){
   if(should_stop())return finish_vss(disc,0);
   for(u32 i=0;i<pixlen+256u;i++)pix[i]=0xa5;
   zero(result,64);
   int cr=((int(*)(u32,void*,void*))cap)(0,pkt,result);
   u32 changed=0,guard=0;
   // Проверяем защитную область и при ошибке CaptureOne: драйвер мог
   // изменить память до возврата ненулевого статуса.
   for(u32 i=pixlen;i<pixlen+256u;i++)if(pix[i]!=0xa5){guard=1;break;}
   if(cr==0){
     for(u32 i=0;i<pixlen;i++)if(pix[i]!=0xa5){changed=1;break;}
   }
   if(cr||!changed||guard){
     ecode("VSS capture ret=",cr);
     consecutive_error++;
     if(guard){eprint("Buffer overrun detected; fatal\n");return finish_vss(disc,9);}
     if(consecutive_error>=3){eprint("Too many capture errors; stopping\n");return finish_vss(disc,10);}
     sleep_ms(500);continue;
   }
   consecutive_error=0;
   for(u32 i=0,j=0;i<pixlen;i+=4){rgb[j++]=pix[i+2];rgb[j++]=pix[i+1];rgb[j++]=pix[i];}
   if(!send_all(hdr,8)||!send_all(rgb,rgblen)){eprint("Output pipe closed\n");return finish_vss(disc,0);}
   frame++;paced_frames++;
   if(should_stop())return finish_vss(disc,0);
   // Счётчик ограничен 100000: разложение деления не переполняет 32 бита
   // и не требует библиотеки 64-битного деления в автономном ARM32-бинарнике.
   monotime(&ts);
   if(paced_frames>=100000u){origin=ts;paced_frames=0;}
   long age=elapsed_ms(&origin,&ts);
   u32 target=(paced_frames/fps)*1000u+((paced_frames%fps)*1000u)/fps;
   if(target>(u32)(age<0?0:age))sleep_ms(target-(u32)(age<0?0:age));
 }
}
/* MTAL запускает собственные потоки; sys_exit завершил бы только основной поток. */
__asm__(".global _start\n_start:\n mov r0, sp\n bl entry\n mov r7, #248\n svc #0\n");
