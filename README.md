## Avaliação ergonômica

### Histórico das avaliações

Em **Dados e resultados**, complete as fotos frontal e lateral, confira a carga e os ajustes e, se desejar incluir OWAS, faça sua classificação manual depois de selecionar as fotos. Toque em **Salvar avaliação** e informe um código de teste, por exemplo `P01_POST01`, usando até 32 letras, números, hífens ou sublinhados. Cada salvamento cria um registro independente. Trocar uma foto limpa a classificação OWAS, que deve ser preenchida novamente para a postura escolhida.

Em **Histórico e exportação**, consulte os registros ou escolha **Exportar CSV** para selecionar o destino do arquivo pelo Android. Nas fotos de uma avaliação, deslize ou use os botões para navegar entre as imagens. Na tela inicial, deslize sobre a foto para alternar entre as vistas frontal e lateral já adicionadas. A exclusão individual pede confirmação e não altera arquivos já exportados.

O histórico guarda data, código, ângulos, indicadores de detecção, entradas, ajustes, RULA, REBA simplificado e, quando informado, OWAS. Novas avaliações também guardam fotos frontal e lateral, com e sem pontos, no armazenamento privado do aplicativo; toque no registro e escolha **Ver fotos**. Registros antigos não incluem fotos ou resultados que não foram salvos. Os valores não são recalculados. Não informe nomes no código. A base e as fotos ficam fora do backup automático; desinstalar o app ou limpar seus dados apaga o histórico e as imagens.

O CSV exporta somente os dados, sem as fotos. Usa UTF-8 com BOM, separador `;`, vírgula decimal e data/hora UTC na coluna `data_hora_utc`. No Excel/Google Sheets, selecione esses parâmetros ao importar, caso necessário. Campos de membros não detectados ficam vazios; os indicadores de detecção e `rula_parcial` devem ser considerados ao analisar os resultados. O arquivo registra as estimativas atuais do protótipo: não contém valores de referência manual nem comprova sua precisão.

### Análise das posturas

Na galeria, selecione fotos frontal e lateral da mesma postura. O aplicativo apresenta estimativas de RULA e REBA; informe carga, atividade, apoio e pega e confirme os ajustes posturais. Os ângulos e ajustes dependem da visibilidade e do enquadramento, e a estimativa REBA é simplificada: não cobre todos os modificadores nem substitui a aplicação integral do método. Revise os resultados com profissional habilitado.

Para OWAS, selecione **Avaliar OWAS** e informe costas, braços, pernas e carga ou força observados. A categoria corresponde a uma única observação. Uma análise da tarefa exige amostragem ao longo do ciclo e consideração das frequências das posturas. A tabela de categorias segue a [Universitat Politècnica de València](https://www.ergonautas.upv.es/metodos/owas/owas-ayuda.php).

# MediaPipe Tasks Pose Landmark Detection Android Demo

### Overview

This is a camera app that can detects landmarks on a person either from continuous camera frames seen by your device's back camera, an image, or a video from the device's gallery using a custom **task** file.

The task file is downloaded by a Gradle script when you build and run the app. You don't need to do any additional steps to download task files into the project explicitly unless you wish to use your own landmark detection task. If you do use your own task file, place it into the app's *assets* directory.

This application should be run on a physical Android device to take advantage of the camera.

![Pose Landmarker Demo](pose_landmarker.png?raw=true "Pose Landmarker Demo")
[Public domain video from Lance Foss](https://www.youtube.com/watch?v=KALIKOd1pbA)

## Build the demo using Android Studio

### Prerequisites

*   The **[Android Studio](https://developer.android.com/studio/index.html)** IDE. This sample has been tested on Android Studio Dolphin.

*   A physical Android device with a minimum OS version of SDK 24 (Android 7.0 -
    Nougat) with developer mode enabled. The process of enabling developer mode
    may vary by device.

### Building

*   Open Android Studio. From the Welcome screen, select Open an existing
    Android Studio project.

*   From the Open File or Project window that appears, navigate to and select
    the mediapipe/examples/pose_landmarker/android directory. Click OK. You may
    be asked if you trust the project. Select Trust.

*   If it asks you to do a Gradle Sync, click OK.

*   With your Android device connected to your computer and developer mode
    enabled, click on the green Run arrow in Android Studio.

### Models used

Downloading, extraction, and placing the models into the *assets* folder is
managed automatically by the **download.gradle** file.
